#!/usr/bin/env bash
# Creates (or reuses) a Gitea release for the current tag and uploads the given files to it.
# Usage: gitea-release.sh <file>...   Env: GITEA_TOKEN, SERVER_URL, REPOSITORY, TAG
set -euo pipefail

: "${GITEA_TOKEN:?GITEA_TOKEN is required}" "${SERVER_URL:?}" "${REPOSITORY:?}" "${TAG:?}"
API="${SERVER_URL%/}/api/v1/repos/${REPOSITORY}"
AUTH=(-H "Authorization: token ${GITEA_TOKEN}")

json_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const v=JSON.parse(s)[process.argv[1]];process.stdout.write(v==null?"":String(v))}catch{process.stdout.write("")}})' "$1"; }

prerelease=false
case "$TAG" in *-*) prerelease=true ;; esac

id="$(curl -fsS "${AUTH[@]}" "$API/releases/tags/${TAG}" 2>/dev/null | json_field id || true)"
if [ -z "$id" ]; then
  notes="$(git log -1 --pretty=%B "$TAG" 2>/dev/null || echo "KaliBabel $TAG")"
  body="$(node -e 'process.stdout.write(JSON.stringify({tag_name:process.argv[1],name:"KaliBabel "+process.argv[1],body:process.argv[2],draft:false,prerelease:process.argv[3]==="true"}))' "$TAG" "$notes" "$prerelease")"
  id="$(curl -fsS "${AUTH[@]}" -H "Content-Type: application/json" -d "$body" "$API/releases" | json_field id)"
fi
[ -n "$id" ] || { echo "::error::Could not create release for $TAG"; exit 1; }
echo "Release id: $id"

for f in "$@"; do
  name="$(basename "$f")"
  echo "Uploading $name"
  curl -fsS "${AUTH[@]}" -F "attachment=@${f}" "$API/releases/${id}/assets?name=${name}" >/dev/null
done
