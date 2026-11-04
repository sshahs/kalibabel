#!/usr/bin/env bash
# Creates the KaliBabel release signing key and the Gitea Actions secrets for it.
# Run this on YOUR machine (needs a JDK for keytool): the private key never has to leave it.
#
#   scripts/release-keystore.sh                     # create release-signing/kalibabel-release.jks + secrets file
#   scripts/release-keystore.sh --keystore my.jks   # use an existing keystore instead (asks for alias/password)
#   GITEA_TOKEN=... scripts/release-keystore.sh --upload
#                                                   # ...and store the secrets in the Gitea repo via the API
#
# GITEA_TOKEN: a Gitea access token with the "write:repository" scope
#              (https://tig.sshah.email/user/settings/applications).
# Env overrides: GITEA_URL (default https://tig.sshah.email), GITEA_REPO (default Cheeba/kalibabel),
#                KEY_ALIAS (default kalibabel), KEYSTORE_PASSWORD (default: random).
#
# BACK UP the keystore and password (password manager + offline copy). If they are lost, installed
# copies of KaliBabel can never be updated in place again: users would have to uninstall first.
set -euo pipefail

GITEA_URL="${GITEA_URL:-https://tig.sshah.email}"
GITEA_REPO="${GITEA_REPO:-Cheeba/kalibabel}"
OUT_DIR="release-signing"
KEYSTORE=""
UPLOAD=false

while [ $# -gt 0 ]; do
  case "$1" in
    --upload) UPLOAD=true ;;
    --keystore) KEYSTORE="${2:?--keystore needs a path}"; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

command -v keytool >/dev/null || { echo "keytool not found: install a JDK (17+) first." >&2; exit 1; }
mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [ -z "$KEYSTORE" ]; then
  KEYSTORE="$OUT_DIR/kalibabel-release.jks"
  [ -e "$KEYSTORE" ] && { echo "$KEYSTORE already exists — refusing to overwrite. Use --keystore $KEYSTORE to reuse it." >&2; exit 1; }
  KEY_ALIAS="${KEY_ALIAS:-kalibabel}"
  if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    KEYSTORE_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')" # 48 hex chars
  fi
  echo "Creating $KEYSTORE (RSA 4096, valid ~27 years)…"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=KaliBabel, O=sshah.email" >/dev/null 2>&1
  chmod 600 "$KEYSTORE"
else
  [ -f "$KEYSTORE" ] || { echo "No such keystore: $KEYSTORE" >&2; exit 1; }
  if [ -z "${KEY_ALIAS:-}" ]; then read -r -p "Key alias: " KEY_ALIAS; fi
  if [ -z "${KEYSTORE_PASSWORD:-}" ]; then read -r -s -p "Keystore password: " KEYSTORE_PASSWORD; echo; fi
fi
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}" # PKCS12 keystores use one password for both

# Verify the alias + password before publishing anything.
if ! keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "Keystore check failed: wrong password or alias '$KEY_ALIAS' not found." >&2
  exit 1
fi
FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" 2>/dev/null \
  | awk -F': ' '/SHA256:/ {print $2; exit}')"
KEYSTORE_BASE64="$(base64 < "$KEYSTORE" | tr -d '\n\r')"

SECRETS_FILE="$OUT_DIR/gitea-secrets.txt"
umask 077
cat > "$SECRETS_FILE" <<EOF
# Gitea → ${GITEA_REPO} → Settings → Actions → Secrets. Add each NAME with the value after '='.
# DELETE THIS FILE once the secrets are stored (keep the .jks + password in your password manager).
KEYSTORE_BASE64=${KEYSTORE_BASE64}
KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD}
KEY_ALIAS=${KEY_ALIAS}
KEY_PASSWORD=${KEY_PASSWORD}
EOF

echo
echo "Keystore:        $KEYSTORE"
echo "Alias:           $KEY_ALIAS"
echo "SHA-256 cert:    ${FINGERPRINT:-unknown}"
echo "Secrets written: $SECRETS_FILE"

if $UPLOAD; then
  : "${GITEA_TOKEN:?Set GITEA_TOKEN (scope write:repository) to use --upload}"
  api="${GITEA_URL%/}/api/v1/repos/${GITEA_REPO}/actions/secrets"
  json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
  for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    value="${!name}"
    code="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$api/$name" \
      -H "Authorization: token ${GITEA_TOKEN}" -H "Content-Type: application/json" \
      -d "{\"data\":\"$(json_escape "$value")\"}")" || code=000
    case "$code" in
      201|204) echo "✓ secret $name stored in ${GITEA_REPO}" ;;
      401|403) echo "✗ $name: HTTP $code — token lacks write:repository or you are not a repo admin" >&2; exit 1 ;;
      404) echo "✗ $name: HTTP 404 — repo ${GITEA_REPO} not found at ${GITEA_URL} (or Actions disabled)" >&2; exit 1 ;;
      *) echo "✗ $name: HTTP $code" >&2; exit 1 ;;
    esac
  done
  rm -f "$SECRETS_FILE"
  echo "Secrets uploaded; removed $SECRETS_FILE."
fi

echo
echo "⚠  Back up $KEYSTORE and its password now. Losing them means future releases can't update installed apps."
