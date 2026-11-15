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

# Certificate digests are public; print them so a mismatch can be diagnosed from the CI log.
# apksigner 36 prints "Signer #1 certificate SHA-256 digest:"; 37 prints "V2 Signer: certificate ..."
# (or "V3.0 Signer:", "V2 Signer #1:"); the pattern accepts both.
# The APK must have exactly one signer. The bundle must have exactly one signer, and only that
# signer's own certificate (keytool's "Certificate #1" under "Signer #1") counts: an upload key
# imported with a certificate chain makes keytool also list the issuer, which must not match.
echo "Build tools: $BUILD_TOOLS_DIR"
apk_certs=$("$BUILD_TOOLS_DIR/apksigner" verify --print-certs "$APK" \
  | sed -nE 's/^(.* )?Signer[^:]*:? certificate SHA-256 digest: *//p' | tr -d ':[:blank:]' | tr 'A-F' 'a-f' | sort -u)
keytool_out=$(keytool -printcert -jarfile "$AAB")
aab_signers=$(grep -c '^Signer #' <<<"$keytool_out" || true)
aab_leaf_certs=$(awk '
  /^Signer #/      { in_leaf = 0; taken = 0 }
  /^Certificate #/ { in_leaf = ($0 ~ /^Certificate #1:/) }
  in_leaf && !taken && /^[[:space:]]*SHA256:/ {
    sub(/^[[:space:]]*SHA256:[[:space:]]*/, ""); gsub(/[:[:blank:]]/, ""); print tolower($0); taken = 1
  }' <<<"$keytool_out" | sort -u)
echo "APK signer SHA-256: ${apk_certs:-<none>}"
echo "Bundle signers: $aab_signers; signer certificate SHA-256: ${aab_leaf_certs:-<none>}"
echo "Bundle certificates listed by keytool: $(grep -c '^Certificate #' <<<"$keytool_out" || true)"
[[ -n "$apk_certs" ]] || fail "apksigner printed no signer certificate for $APK"
[[ "$(wc -l <<<"$apk_certs")" -eq 1 ]] || fail "APK has more than one signer certificate"
[[ "$aab_signers" -eq 1 ]] || fail "bundle has $aab_signers signers, expected exactly 1"
[[ -n "$aab_leaf_certs" && "$(wc -l <<<"$aab_leaf_certs")" -eq 1 ]] \
  || fail "could not read the bundle signer's certificate"
[[ "$apk_certs" == "$aab_leaf_certs" ]] \
  || fail "APK signer does not match the bundle's upload certificate"
apk_cert=$apk_certs

echo "Tester APK ok: $package $version_name (versionCode $version_code), signer SHA-256 $apk_cert."
