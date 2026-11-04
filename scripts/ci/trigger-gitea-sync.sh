#!/usr/bin/env bash
# Asks the Gitea pull mirror to sync now, so the tag GitHub just published (build-<N> or v*) reaches
# Gitea immediately and its release run starts without waiting for the mirror interval.
# Optional: does nothing unless GITEA_SYNC_TOKEN is set (a Gitea token with write:repository).
# Env: GITEA_SYNC_TOKEN, GITEA_REPO_API (e.g. https://tig.sshah.email/api/v1/repos/Cheeba/kalibabel)
set -uo pipefail

if [ -z "${GITEA_SYNC_TOKEN:-}" ]; then
  echo "No GITEA_SYNC_TOKEN secret: the Gitea mirror will pick this up at its next scheduled sync."
  exit 0
fi
api="${GITEA_REPO_API:?GITEA_REPO_API is required}"
code="$(curl -sS -m 30 -o /tmp/gitea-sync.json -w '%{http_code}' -X POST \
  -H "Authorization: token ${GITEA_SYNC_TOKEN}" "${api%/}/mirror-sync")" || code=000
case "$code" in
  200|204) echo "Gitea mirror sync requested (${api%/})." ;;
  401|403) echo "::warning::Gitea refused the mirror sync (HTTP $code): GITEA_SYNC_TOKEN needs write:repository on the mirror repo." ;;
  400) echo "::warning::Gitea says mirroring is disabled or this repo is not a mirror (HTTP 400)." ;;
  000) echo "::warning::Could not reach ${api} from the runner." ;;
  *) echo "::warning::Gitea mirror sync failed (HTTP $code): $(head -c 300 /tmp/gitea-sync.json 2>/dev/null)" ;;
esac
exit 0 # never fail a release because the mirror could not be poked
