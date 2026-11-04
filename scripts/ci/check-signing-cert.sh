#!/usr/bin/env bash
# Checks that APKs are signed with the release key pinned in .github/signing-cert-sha256.
# Android only installs an update signed with the same key as the installed app, so a release built
# with any other key (wrong secret on one forge, debug key) must never be published.
# Release builds (v* tag / RELEASE_REF) fail on a mismatch; other builds only warn.
# Usage: check-signing-cert.sh <apk>...
set -euo pipefail

PIN_FILE="${PIN_FILE:-.github/signing-cert-sha256}"
ref="${RELEASE_REF:-${GITHUB_REF:-}}"
is_release=false
case "$ref" in refs/tags/v*) is_release=true ;; esac

if [ ! -f "$PIN_FILE" ]; then
  echo "::warning::$PIN_FILE missing: signing certificate not checked."
  exit 0
fi
expected="$(tr -d '[:space:]' < "$PIN_FILE" | tr 'A-F' 'a-f')"
signer="${ANDROID_HOME:-}/build-tools/${ANDROID_BUILD_TOOLS:-36.0.0}/apksigner"
[ -x "$signer" ] || signer="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[ -x "$signer" ] || { echo "::error::apksigner not found under \$ANDROID_HOME"; exit 1; }

[ "$#" -gt 0 ] || { echo "::warning::No APKs to check."; exit 0; }
bad=0
for apk in "$@"; do
  [ -f "$apk" ] || continue
  actual="$("$signer" verify --print-certs "$apk" 2>/dev/null | awk -F': ' '/SHA-256 digest/ {print $2; exit}' | tr 'A-F' 'a-f')"
  if [ "$actual" = "$expected" ]; then
    echo "✓ $(basename "$apk") is signed with the release key"
  else
    bad=1
    msg="$(basename "$apk") is signed with ${actual:-an unreadable certificate}, not the release key $expected — it would not install as an update."
    if $is_release; then echo "::error::$msg"; else echo "::warning::$msg"; fi
  fi
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  if [ "$bad" = 0 ]; then echo "match=true" >> "$GITHUB_OUTPUT"; else echo "match=false" >> "$GITHUB_OUTPUT"; fi
fi
if [ "$bad" = 1 ] && $is_release; then
  echo "::error::Not publishing: check that KEYSTORE_BASE64 on this forge is the same key as on the other one."
  exit 1
fi
exit 0
