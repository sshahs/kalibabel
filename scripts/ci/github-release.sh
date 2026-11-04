#!/usr/bin/env bash
# Publishes files to a GitHub release using the gh CLI. Re-running is safe: notes are refreshed and
# same-named assets are replaced (--clobber).
#
# Tag: $TAG, else the tag in $RELEASE_REF / $GITHUB_REF. If TARGET_SHA is set and the tag does not
# exist yet, gh creates it on that commit (used for the per-push build-<N> prereleases); otherwise
# the tag must already exist.
# Env: GH_TOKEN, GITHUB_REPOSITORY; optional TAG, TITLE, NOTES_FILE, TARGET_SHA, PRERELEASE=true, DRAFT=true
set -euo pipefail
: "${GH_TOKEN:?GH_TOKEN is required}" "${GITHUB_REPOSITORY:?}"
[ "$#" -gt 0 ] || { echo "::error::no files to upload"; exit 1; }

ref="${RELEASE_REF:-${GITHUB_REF:-}}"
tag="${TAG:-${ref#refs/tags/}}"
title="${TITLE:-KaliBabel $tag}"
notes="${NOTES_FILE:-}"
flags=()
prerelease="${PRERELEASE:-}"
case "$tag" in *-*) prerelease=true ;; esac
if [ "$prerelease" = "true" ]; then flags+=(--prerelease --latest=false); else flags+=(--latest); fi
[ "${DRAFT:-}" = "true" ] && flags+=(--draft)
if [ -n "${TARGET_SHA:-}" ]; then target=(--target "$TARGET_SHA"); else target=(--verify-tag); fi

if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Updating existing release $tag"
  gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --title "$title" ${notes:+--notes-file "$notes"}
  gh release upload "$tag" --repo "$GITHUB_REPOSITORY" --clobber "$@"
else
  echo "Creating release $tag"
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" "${target[@]}" --title "$title" \
    ${notes:+--notes-file "$notes"} "${flags[@]}" "$@"
fi
url="$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json url -q .url 2>/dev/null || true)"
url="${url:-https://github.com/${GITHUB_REPOSITORY}/releases/tag/${tag}}"
echo "Release published: $url"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "### 📦 [${title}](${url})" >> "$GITHUB_STEP_SUMMARY"
exit 0
