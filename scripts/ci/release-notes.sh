#!/usr/bin/env bash
# Writes dist/RELEASE_NOTES.md for the tag in $TAG / $RELEASE_REF / $GITHUB_REF: install guide, then
# .github/release-notes/<tag>.md if it exists (hand-written notes, as in KaliScriber), otherwise the
# commits since the previous v* tag. Ends with links to the commit and the CI run.
set -euo pipefail

ref="${RELEASE_REF:-${GITHUB_REF:-}}"
tag="${TAG:-${ref#refs/tags/}}"
notes_file=".github/release-notes/${tag}.md"
head="$tag"
git rev-parse -q --verify "refs/tags/${tag}" >/dev/null || head="HEAD" # build-<N>: tag created on publish
prev="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "${head}^" 2>/dev/null || true)"
range="$head"
if [ -n "$prev" ]; then range="${prev}..${head}"; fi
mkdir -p dist
{
  echo "## Install"
  echo
  echo "Most phones: **KaliBabel-${KALIBABEL_VERSION_NAME}-arm64-v8a-release.apk**."
  echo "Older 32-bit phones: \`armeabi-v7a\`. Emulators/Chromebooks: \`x86_64\`. Not sure: \`universal\` (larger)."
  echo "Verify downloads with \`sha256sum -c SHA256SUMS.txt\`."
  if [ -n "${SIGNING_CERT_SHA256:-}" ]; then echo "Signing certificate SHA-256: \`${SIGNING_CERT_SHA256}\`"; fi
  echo
  if [ -f "$notes_file" ]; then
    cat "$notes_file"
  else
    if [ -n "$prev" ]; then echo "## Changes since ${prev}"; else echo "## Changes"; fi
    echo
    git log -n 100 --no-merges --pretty='- %s (%h)' "$range"
  fi
  if [ -n "${GITHUB_SERVER_URL:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    repo_url="${GITHUB_SERVER_URL%/}/${GITHUB_REPOSITORY}"
    sha="$(git rev-parse HEAD)"
    echo
    echo "---"
    echo "Built on [${GITHUB_REPOSITORY}](${repo_url}) from commit [${sha:0:7}](${repo_url}/commit/${sha})${RUN_URL:+ by [this run](${RUN_URL})}."
  fi
} > dist/RELEASE_NOTES.md
cat dist/RELEASE_NOTES.md
