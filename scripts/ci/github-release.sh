#!/usr/bin/env bash
# Publishes files to the GitHub release for the tag in $GITHUB_REF using the gh CLI.
# Re-running is safe: notes are refreshed and same-named assets are replaced (--clobber).
# Env: GH_TOKEN, GITHUB_REPOSITORY; optional NOTES_FILE, DRAFT=true
set -euo pipefail
: "${GH_TOKEN:?GH_TOKEN is required}" "${GITHUB_REPOSITORY:?}"
[ "$#" -gt 0 ] || { echo "::error::no files to upload"; exit 1; }

tag="${GITHUB_REF#refs/tags/}"
notes="${NOTES_FILE:-}"
flags=()
case "$tag" in *-*) flags+=(--prerelease) ;; esac
[ "${DRAFT:-}" = "true" ] && flags+=(--draft)

if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Updating existing release $tag"
  gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --title "KaliBabel $tag" ${notes:+--notes-file "$notes"}
  gh release upload "$tag" --repo "$GITHUB_REPOSITORY" --clobber "$@"
else
  echo "Creating release $tag"
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" --verify-tag --title "KaliBabel $tag" \
    ${notes:+--notes-file "$notes"} "${flags[@]}" "$@"
fi
url="$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json url -q .url 2>/dev/null || true)"
url="${url:-https://github.com/${GITHUB_REPOSITORY}/releases/tag/${tag}}"
echo "Release published: $url"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "### 📦 [KaliBabel ${tag}](${url})" >> "$GITHUB_STEP_SUMMARY"
exit 0
