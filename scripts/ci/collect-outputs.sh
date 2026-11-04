#!/usr/bin/env bash
# Copies the built APKs to dist/ with versioned names, writes SHA256SUMS.txt, gathers test/lint
# reports into reports/ and exports SIGNING_CERT_SHA256 (via $GITHUB_ENV when set).
set -uo pipefail

: "${KALIBABEL_VERSION_NAME:?run scripts/ci/version.sh first}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
mkdir -p dist reports

for f in app/build/outputs/apk/release/*.apk; do
  [ -e "$f" ] || continue
  cp "$f" "dist/KaliBabel-${KALIBABEL_VERSION_NAME}-$(basename "$f" | sed 's/^app-//')"
done
for f in app/build/outputs/apk/debug/*universal*.apk; do
  [ -e "$f" ] && cp "$f" "dist/KaliBabel-${KALIBABEL_VERSION_NAME}-universal-debug.apk"
done
cp -r app/build/reports/. reports/ 2>/dev/null || true
cp -r app/build/screenshots reports/screenshots 2>/dev/null || true
cp -r app/build/outputs/mapping reports/r8-mapping 2>/dev/null || true

if ls dist/*-release.apk >/dev/null 2>&1; then
  (cd dist && sha256sum *-release.apk > SHA256SUMS.txt)
  apk="$(ls dist/*-arm64-v8a-release.apk 2>/dev/null | head -n1)"
  signer="${ANDROID_HOME:-}/build-tools/${BUILD_TOOLS}/apksigner"
  if [ -n "$apk" ] && [ -x "$signer" ]; then
    cert="$("$signer" verify --print-certs "$apk" 2>/dev/null | awk -F': ' '/SHA-256 digest/ {print $2; exit}')"
    if [ -n "$cert" ]; then
      [ -n "${GITHUB_ENV:-}" ] && echo "SIGNING_CERT_SHA256=$cert" >> "$GITHUB_ENV"
      echo "Signing certificate SHA-256: $cert"
    fi
  fi
fi
ls -la dist
exit 0
