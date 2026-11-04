#!/usr/bin/env bash
# Installs the Android SDK pieces the build needs into $ANDROID_HOME (default ~/android-sdk).
# Idempotent: with the SDK directory restored from cache it only verifies the packages.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-16111833}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-37.0}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"

SUDO=""
if [ "$(id -u)" != "0" ] && command -v sudo >/dev/null 2>&1; then SUDO="sudo"; fi

need() { command -v "$1" >/dev/null 2>&1; }
if ! need unzip || ! need curl; then
  echo "Installing unzip/curl…"
  if need apt-get; then
    $SUDO apt-get update -qq && $SUDO apt-get install -y -qq unzip curl >/dev/null
  elif need apk; then
    $SUDO apk add --no-cache unzip curl bash >/dev/null
  else
    echo "::error::unzip and curl are required" && exit 1
  fi
fi

case "$(uname -m)" in
  x86_64|amd64) ;;
  *) echo "::warning::Android build-tools (aapt2) only ship for x86_64 Linux; use an x86_64 act_runner." ;;
esac

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Downloading Android command-line tools $CMDLINE_TOOLS_VERSION…"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 --retry-delay 3 -o "$tmp/clt.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" --install "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" | grep -v '^\[' || true

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "ANDROID_HOME=$SDK" >> "$GITHUB_ENV"
  echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
fi
echo "Android SDK ready at $SDK"
