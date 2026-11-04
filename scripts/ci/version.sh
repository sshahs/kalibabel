#!/usr/bin/env bash
# Exports KALIBABEL_VERSION_NAME / KALIBABEL_VERSION_CODE for Gradle (via $GITHUB_ENV when set).
#
# versionCode = number of commits reachable from HEAD. GitHub and Gitea both build full clones of
# the same history, so the same commit gets the same code on both forges, and it only grows along
# main — any newer build can update an older install regardless of where it was downloaded.
set -euo pipefail

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  echo "::error::Shallow clone: versionCode needs full history (actions/checkout with fetch-depth: 0)."
  exit 1
fi

code="$(git rev-list --count HEAD)"
case "${GITHUB_REF:-}" in
  refs/tags/v*) name="${GITHUB_REF#refs/tags/v}" ;;
  *)
    described="$(git describe --tags --match 'v[0-9]*' 2>/dev/null || true)"
    if [ -n "$described" ]; then
      name="${described#v}"                                     # e.g. 0.1.0-3-g1a2b3c4
    else
      name="0.0.0-${code}-g$(git rev-parse --short HEAD)"       # no release tag yet
    fi
    ;;
esac

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "KALIBABEL_VERSION_NAME=$name" >> "$GITHUB_ENV"
  echo "KALIBABEL_VERSION_CODE=$code" >> "$GITHUB_ENV"
fi
echo "Version $name (code $code)"
