#!/usr/bin/env bash
# Publishes files as assets of the Gitea release for $TAG on $REPOSITORY.
#
# Works on pull mirrors: the mirror sync creates a tag-only release for a new tag, and
# POST /releases converts it into a full release (Gitea >= 1.21.5 keeps it across later syncs).
# Re-running is safe: the release is updated and same-named assets are replaced.
#
# Usage: gitea-release.sh <file>...
# Env:   GITEA_TOKEN, SERVER_URL, REPOSITORY (owner/repo), TAG
#        optional: TITLE, TARGET_SHA, NOTES_FILE (markdown release notes), DRAFT=true
set -euo pipefail

: "${GITEA_TOKEN:?GITEA_TOKEN is required}" "${SERVER_URL:?SERVER_URL is required}"
: "${REPOSITORY:?REPOSITORY is required}" "${TAG:?TAG is required}"
[ "$#" -gt 0 ] || { echo "::error::no files to upload"; exit 1; }

API="${SERVER_URL%/}/api/v1/repos/${REPOSITORY}"
HTTP_CODE=""
BODY=""

# api METHOD PATH [curl args...]  -> sets HTTP_CODE and BODY
api() {
  local method=$1 path=$2 tmp
  shift 2
  tmp="$(mktemp)"
  HTTP_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" \
    -H "Authorization: token ${GITEA_TOKEN}" -H "Accept: application/json" "$@" "$API$path")" || true
  [ -n "$HTTP_CODE" ] || HTTP_CODE=000
  BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

fail() {
  local hint=""
  case "$HTTP_CODE" in
    000) hint=" — could not reach ${SERVER_URL} from the runner (check ROOT_URL / runner networking)" ;;
    401|403) hint=" — the token cannot write releases: set Actions token permissions to permissive for this repo, or add a RELEASE_TOKEN secret (scope: write:repository)" ;;
    404) hint=" — repository ${REPOSITORY} not found, or its Releases unit is disabled (Settings → Repository → Releases)" ;;
    413) hint=" — upload too large: raise the reverse proxy body limit (e.g. nginx client_max_body_size 200m) and [repository.release] FILE_MAX_SIZE" ;;
    400) hint=" — if this is 'file type is not allowed', add .apk/.txt to [repository.release] ALLOWED_TYPES" ;;
  esac
  echo "::error::$1 (HTTP ${HTTP_CODE})${hint}"
  printf '%s\n' "$BODY" | head -c 800
  echo
  exit 1
}

# json <field> : reads JSON from stdin, prints a top-level field
json() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const v=JSON.parse(s)[process.argv[1]];process.stdout.write(v==null?"":String(v))}catch(e){}})' "$1"; }

# asset_id <name> : reads an asset array from stdin, prints the id of the asset with that name
asset_id() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const a=JSON.parse(s).find(x=>x.name===process.argv[1]);if(a)process.stdout.write(String(a.id))}catch(e){}})' "$1"; }

prerelease=false
case "$TAG" in *-*) prerelease=true ;; esac
notes="KaliBabel ${TAG}"
if [ -n "${NOTES_FILE:-}" ] && [ -f "$NOTES_FILE" ]; then notes="$(cat "$NOTES_FILE")"; fi
draft=false
[ "${DRAFT:-}" = "true" ] && draft=true
payload="$(node -e 'const [tag,notes,pre,sha,draft,title]=process.argv.slice(1);const o={tag_name:tag,name:title||("KaliBabel "+tag),body:notes,draft:draft==="true",prerelease:pre==="true"};if(sha)o.target_commitish=sha;process.stdout.write(JSON.stringify(o))' \
  "$TAG" "$notes" "$prerelease" "${TARGET_SHA:-}" "$draft" "${TITLE:-}")"

api GET "/releases/tags/${TAG}"
case "$HTTP_CODE" in
  200)
    id="$(printf '%s' "$BODY" | json id)"
    echo "Updating existing release ${TAG} (id ${id})"
    api PATCH "/releases/${id}" -H "Content-Type: application/json" -d "$payload"
    [ "$HTTP_CODE" = "200" ] || fail "Could not update release ${TAG}"
    ;;
  404)
    # Not found, or only a tag-only entry created by the mirror sync: POST creates/converts it.
    echo "Creating release ${TAG}"
    api POST "/releases" -H "Content-Type: application/json" -d "$payload"
    if [ "$HTTP_CODE" = "409" ]; then
      api GET "/releases/tags/${TAG}" # created concurrently by another run
      [ "$HTTP_CODE" = "200" ] || fail "Release ${TAG} conflicted but cannot be read"
    elif [ "$HTTP_CODE" != "201" ]; then
      fail "Could not create release ${TAG}"
    fi
    id="$(printf '%s' "$BODY" | json id)"
    ;;
  *) fail "Could not look up release ${TAG}" ;;
esac
[ -n "$id" ] || fail "Release ${TAG} has no id"
html_url="$(printf '%s' "$BODY" | json html_url)"

api GET "/releases/${id}/assets"
[ "$HTTP_CODE" = "200" ] || fail "Could not list assets of release ${TAG}"
assets="$BODY"

for f in "$@"; do
  [ -f "$f" ] || { echo "::warning::skipping missing file $f"; continue; }
  name="$(basename "$f")"
  existing="$(printf '%s' "$assets" | asset_id "$name")"
  if [ -n "$existing" ]; then
    echo "Replacing $name"
    api DELETE "/releases/${id}/assets/${existing}"
    [ "$HTTP_CODE" = "204" ] || fail "Could not delete old asset $name"
  else
    echo "Uploading $name ($(du -h "$f" | cut -f1))"
  fi
  api POST "/releases/${id}/assets?name=${name}" -F "attachment=@${f}"
  [ "$HTTP_CODE" = "201" ] || fail "Could not upload $name"
done

echo "Release published: ${html_url:-${SERVER_URL%/}/${REPOSITORY}/releases/tag/${TAG}}"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### 📦 [KaliBabel ${TAG}](${html_url:-${SERVER_URL%/}/${REPOSITORY}/releases/tag/${TAG}})" >> "$GITHUB_STEP_SUMMARY"
fi
