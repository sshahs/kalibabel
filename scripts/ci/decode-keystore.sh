#!/usr/bin/env bash
# CI step: decodes the signing keystore from secrets, validates it and exports the Gradle signing env.
# Tag (release) builds fail without signing secrets unless ALLOW_DEBUG_SIGNED_RELEASE=true, because a
# debug-signed release could never be upgraded to by a properly signed one.
set -euo pipefail

is_release=false
case "${GITHUB_REF:-}" in refs/tags/v*) is_release=true ;; esac

if [ -z "${KEYSTORE_BASE64:-}" ]; then
  if $is_release && [ "${ALLOW_DEBUG_SIGNED_RELEASE:-}" != "true" ]; then
    echo "::error::Release tag ${GITHUB_REF#refs/tags/} needs the KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD secrets (create them with scripts/release-keystore.sh)."
    exit 1
  fi
  echo "::warning::No KEYSTORE_BASE64 secret: release APKs are signed with the debug key."
  exit 0
fi

: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD secret is missing}" "${KEY_ALIAS:?KEY_ALIAS secret is missing}"
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
keystore="${RUNNER_TEMP:-/tmp}/release.jks"
printf '%s' "$KEYSTORE_BASE64" | tr -d ' \n\r' | base64 -d > "$keystore" \
  || { echo "::error::KEYSTORE_BASE64 is not valid base64"; exit 1; }
chmod 600 "$keystore"

if ! keytool -list -keystore "$keystore" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "::error::Keystore check failed: KEYSTORE_PASSWORD is wrong or alias '$KEY_ALIAS' is not in KEYSTORE_BASE64."
  exit 1
fi

{
  echo "KALIBABEL_KEYSTORE_FILE=$keystore"
  echo "KALIBABEL_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "KALIBABEL_KEY_ALIAS=$KEY_ALIAS"
  echo "KALIBABEL_KEY_PASSWORD=$KEY_PASSWORD"
} >> "$GITHUB_ENV"
echo "Release builds will be signed with alias '$KEY_ALIAS'."
