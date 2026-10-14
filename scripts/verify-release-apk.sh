#!/usr/bin/env bash
# Checks that an APK is signed with the Cinexperience release key, so it installs
# as an update over the existing app (spec §14.4). Used by CI and the release workflow.
#
#   scripts/verify-release-apk.sh path/to/app.apk
#
# The expected certificate SHA-256 is in signing/release-cert.sha256 (public, not a
# secret). CINEX_RELEASE_CERT_SHA256 overrides it, e.g. to check a build signed with
# a throwaway local key. APKSIGNER overrides the apksigner binary (default:
# $ANDROID_HOME/build-tools/36.1.0/apksigner).
set -euo pipefail

apk="${1:?usage: verify-release-apk.sh <apk>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected="${CINEX_RELEASE_CERT_SHA256:-$(tr -d '[:space:]' < "$root/signing/release-cert.sha256")}"
expected="$(tr 'A-F' 'a-f' <<< "$expected")"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
apksigner="${APKSIGNER:-$sdk/build-tools/36.1.0/apksigner}"

fail() {
  echo "::error::$1" >&2
  exit 1
}

[ -f "$apk" ] || fail "$apk does not exist."
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "The expected certificate SHA-256 is not 64 hex digits: '$expected'."

if ! output="$("$apksigner" verify --print-certs "$apk" 2>&1)"; then
  echo "$output" >&2
  fail "apksigner could not verify $apk: it is unsigned or corrupted."
fi
echo "$output"

# Exactly one signer, with the release certificate.
digests="$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' <<< "$output" | tr 'A-F' 'a-f')"
if [ "$digests" != "$expected" ]; then
  fail "APK not signed with the Cinexperience release key — publishing it would not install over the existing app. Expected certificate SHA-256 $expected, found: ${digests:-none}."
fi
echo "OK: $apk is signed with the release key ($expected)."
