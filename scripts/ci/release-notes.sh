#!/usr/bin/env bash
# Writes dist/RELEASE_NOTES.md for the tag in $RELEASE_REF / $GITHUB_REF: install guide + commits since the previous tag.
set -euo pipefail

ref="${RELEASE_REF:-$GITHUB_REF}"
tag="${ref#refs/tags/}"
prev="$(git describe --tags --abbrev=0 --match 'v[0-9]*' "${tag}^" 2>/dev/null || true)"
range="$tag"
if [ -n "$prev" ]; then range="${prev}..${tag}"; fi
mkdir -p dist
{
  echo "## Install"
  echo
  echo "Most phones: **KaliBabel-${KALIBABEL_VERSION_NAME}-arm64-v8a-release.apk**."
  echo "Older 32-bit phones: \`armeabi-v7a\`. Emulators/Chromebooks: \`x86_64\`. Not sure: \`universal\` (larger)."
  echo "Verify downloads with \`sha256sum -c SHA256SUMS.txt\`."
  if [ -n "${SIGNING_CERT_SHA256:-}" ]; then echo "Signing certificate SHA-256: \`${SIGNING_CERT_SHA256}\`"; fi
  echo
  if [ -n "$prev" ]; then echo "## Changes since ${prev}"; else echo "## Changes"; fi
  echo
  git log -n 100 --no-merges --pretty='- %s (%h)' "$range"
} > dist/RELEASE_NOTES.md
cat dist/RELEASE_NOTES.md
