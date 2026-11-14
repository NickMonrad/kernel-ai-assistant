#!/usr/bin/env bash
# Verifies the signed release APK before it is allowed to accompany a Play upload.
# Run by :app:verifyTesterApkForPlayPublish, which publishReleaseBundle depends on, so a
# failure here stops the run before anything reaches Play.
#
# Usage: verify_tester_apk.sh <app-release.apk> <app-release.aab> <available-version-codes.txt> <version prefix>
# Requires BUILD_TOOLS_DIR (aapt2, apksigner) and keytool on PATH.
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <apk> <aab> <available-version-codes.txt> <version prefix e.g. 0.1>" >&2
  exit 2
fi
APK=$1
AAB=$2
CODES=$3
PREFIX=$4

fail() { echo "Tester APK verification failed: $*" >&2; exit 1; }

: "${BUILD_TOOLS_DIR:?BUILD_TOOLS_DIR must point at the Android build-tools directory}"
[[ -f "$APK" ]] || fail "missing APK $APK"
[[ -f "$AAB" ]] || fail "missing bundle $AAB"
[[ -s "$CODES" ]] || fail "GPP did not write $CODES"

badging=$("$BUILD_TOOLS_DIR/aapt2" dump badging "$APK")
package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)
version_code=$(sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
version_name=$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)

[[ "$package" == "ai.jandal.assistant" ]] || fail "package is '$package', expected ai.jandal.assistant"
[[ -n "$version_code" ]] || fail "no versionCode in APK"
[[ "$version_name" == "$PREFIX.$version_code" ]] \
  || fail "versionName '$version_name' must be '$PREFIX.$version_code'"

# GPP writes one line per output: the Play-assigned code for this run.
[[ "$(wc -l < "$CODES")" -eq 1 ]] || fail "expected exactly one Play versionCode in $CODES"
play_code=$(tr -d '[:space:]' < "$CODES")
[[ "$play_code" == "$version_code" ]] \
  || fail "APK versionCode $version_code differs from Play-assigned versionCode $play_code"

apk_cert=$("$BUILD_TOOLS_DIR/apksigner" verify --print-certs "$APK" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-F' 'a-f')
aab_cert=$(keytool -printcert -jarfile "$AAB" | sed -n 's/^.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
[[ -n "$apk_cert" && "$apk_cert" == "$aab_cert" ]] \
  || fail "APK signer does not match the bundle's upload certificate"

echo "Tester APK ok: $package $version_name (versionCode $version_code), signer SHA-256 $apk_cert."
