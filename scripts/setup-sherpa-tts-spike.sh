#!/usr/bin/env bash
# =============================================================================
# scripts/setup-sherpa-tts-spike.sh
#
# Downloads the Sherpa-ONNX AAR needed for the Sherpa TTS spike.
#
# NOTHING downloaded here is committed to git — see .gitignore patterns:
#   third_party/sherpa-onnx/
#
# Usage (run from repo root):
#   bash scripts/setup-sherpa-tts-spike.sh
#
# After running, rebuild so Gradle picks up the new AAR:
#   ./gradlew :app:assembleDebug
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SHERPA_VERSION="1.13.0"
AAR_FILE="sherpa-onnx-${SHERPA_VERSION}.aar"
AAR_DIR="${REPO_ROOT}/third_party/sherpa-onnx"
AAR_PATH="${AAR_DIR}/${AAR_FILE}"
SHERPA_AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${AAR_FILE}"

info()  { echo "[sherpa-tts-spike] $*"; }
warn()  { echo "[sherpa-tts-spike] WARN: $*" >&2; }
error() { echo "[sherpa-tts-spike] ERROR: $*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || error "'$1' not found. Please install it and re-run."
}

download_if_missing() {
    local url="$1"
    local dest="$2"
    local label="$3"

    if [[ -f "${dest}" ]]; then
        info "${label} already present: ${dest} (skipping download)"
        return 0
    fi

    mkdir -p "$(dirname "${dest}")"
    info "Downloading ${label}..."
    info "  URL: ${url}"
    if ! curl --fail --location --progress-bar -o "${dest}" "${url}"; then
        rm -f "${dest}"
        return 1
    fi
    info "${label} saved to: ${dest}"
}

require_cmd curl

info "Repo root: ${REPO_ROOT}"

mkdir -p "${AAR_DIR}"

if [[ -f "${AAR_PATH}" ]]; then
    info "AAR already present: ${AAR_PATH} (skipping download)"
else
    info "Downloading Sherpa-ONNX ${SHERPA_VERSION} AAR..."
    info "  URL: ${SHERPA_AAR_URL}"
    if ! curl --fail --location --progress-bar -o "${AAR_PATH}" "${SHERPA_AAR_URL}"; then
        rm -f "${AAR_PATH}"
        error "Failed to download AAR from ${SHERPA_AAR_URL}
  Check the release page: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v${SHERPA_VERSION}
  If the URL has changed, update SHERPA_AAR_URL in this script."
    fi
    info "AAR saved to: ${AAR_PATH}"
fi

AAR_SIZE=$(wc -c < "${AAR_PATH}" | tr -d '[:space:]')
info "AAR size: ${AAR_SIZE} bytes"
if [[ "${AAR_SIZE}" -lt 1000000 ]]; then
    warn "AAR appears unexpectedly small (${AAR_SIZE} bytes). It may be a redirect/error page."
    warn "Delete ${AAR_PATH} and re-run if the build fails with class-not-found errors."
fi

echo ""
echo "================================================================="
  echo "  Sherpa-ONNX TTS spike setup complete"
echo "================================================================="
echo ""
echo "  AAR: ${AAR_PATH}"
echo ""
echo "  Notes:"
echo "    - Voice packs are now downloaded on device from Settings -> Voice."
echo "    - The build no longer bundles local sherpa-tts assets into the APK."
echo "    - Android TTS remains available as the runtime fallback."
echo ""
echo "  Next steps:"
echo "    1. Rebuild the project so Gradle picks up the new AAR:"
echo "       ./gradlew :app:assembleDebug"
echo ""
echo "    2. Install on device, open Settings -> Voice, and download a Sherpa voice pack."
echo "       Logcat tag 'KernelAI' shows which backend is selected."
echo ""
echo "  To revert to Android TTS only (without touching code):"
echo "    - Remove or move ${AAR_PATH}"
echo "    - Rebuild — SherpaOnnxVoiceOutputController will return Unavailable"
echo "      and FallbackVoiceOutputController will route to Android TTS."
echo "================================================================="
