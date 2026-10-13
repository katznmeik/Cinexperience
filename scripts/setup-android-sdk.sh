#!/usr/bin/env bash
# Installs the Android SDK pieces needed to build Cinexperience. Idempotent:
# running it again only installs what is missing.
#
#   scripts/setup-android-sdk.sh
#
# SDK location: $ANDROID_HOME, else $ANDROID_SDK_ROOT, else $HOME/android-sdk.
# Afterwards local.properties (sdk.dir=...) is written to the repository root.
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
# Command-line tools 23.0 (stable channel).
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-16111833}"
PACKAGES=(
  "platform-tools"
  "platforms;android-37.0"   # compileSdk (see gradle/libs.versions.toml)
  "platforms;android-36"     # targetSdk
  "build-tools;36.1.0"
)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

retry() {
  local delay=2
  for attempt in 1 2 3 4 5; do
    if "$@"; then return 0; fi
    echo "Attempt $attempt failed, retrying in ${delay}s..." >&2
    sleep "$delay"
    delay=$((delay * 2))
  done
  return 1
}

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "Installing Android command-line tools ($CMDLINE_TOOLS_BUILD) into $SDK_ROOT"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  retry curl -fsSL -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  mkdir -p "$tmp/unzipped"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/unzipped"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/unzipped/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

missing=()
for pkg in "${PACKAGES[@]}"; do
  [[ -d "$SDK_ROOT/${pkg//;//}" ]] || missing+=("$pkg")
done

if (( ${#missing[@]} > 0 )); then
  echo "Accepting licenses"
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
  echo "Installing: ${missing[*]}"
  retry "$SDKMANAGER" --sdk_root="$SDK_ROOT" "${missing[@]}"
else
  echo "All SDK packages already installed in $SDK_ROOT"
fi

echo "sdk.dir=$SDK_ROOT" > "$REPO_ROOT/local.properties"
echo "Wrote $REPO_ROOT/local.properties"

# Cloud machines: Maven Central often answers HTTP 429. Route Maven Central
# traffic through Google's mirror via a user-level Gradle init script.
# Opt out with CINEX_NO_MAVEN_MIRROR=1.
if [[ "${CINEX_NO_MAVEN_MIRROR:-0}" != "1" ]]; then
  mkdir -p "$HOME/.gradle/init.d"
  cp "$REPO_ROOT/scripts/maven-central-mirror.init.gradle" "$HOME/.gradle/init.d/maven-central-mirror.gradle"
  echo "Installed Maven Central mirror init script into $HOME/.gradle/init.d"
fi
