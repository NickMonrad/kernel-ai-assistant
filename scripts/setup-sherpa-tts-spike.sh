#!/usr/bin/env bash
# =============================================================================
# scripts/setup-sherpa-tts-spike.sh
#
# Downloads the Sherpa-ONNX AAR and prepares a small locally converted en_GB
# Piper voice pack so the Sherpa TTS spike can be compiled and exercised.
#
# NOTHING downloaded here is committed to git — see .gitignore patterns:
#   third_party/sherpa-onnx/
#   core/voice/src/main/assets/sherpa-tts/
#
# Usage (run from repo root):
#   bash scripts/setup-sherpa-tts-spike.sh
#
# Optional overrides:
#   PIPER_BASE_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main \
#   PIPER_VOICE_KEYS=en_GB-jenny_dioco-medium,en_GB-northern_english_male-medium \
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

PIPER_BASE_URL="${PIPER_BASE_URL:-https://huggingface.co/rhasspy/piper-voices/resolve/main}"
PIPER_VOICE_KEYS="${PIPER_VOICE_KEYS:-}"
ESPEAK_ARCHIVE="espeak-ng-data.tar.bz2"
ESPEAK_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${ESPEAK_ARCHIVE}"

ASSETS_DIR="${REPO_ROOT}/core/voice/src/main/assets/sherpa-tts"
DOWNLOADS_DIR="${ASSETS_DIR}/.downloads"
ESPEAK_ARCHIVE_PATH="${DOWNLOADS_DIR}/${ESPEAK_ARCHIVE}"

VOICE_SPECS=(
    "en_GB-jenny_dioco-medium|en/en_GB/jenny_dioco/medium|vits-piper-en_GB-jenny_dioco-medium"
    "en_GB-southern_english_female-low|en/en_GB/southern_english_female/low|vits-piper-en_GB-southern_english_female-low"
    "en_GB-northern_english_male-medium|en/en_GB/northern_english_male/medium|vits-piper-en_GB-northern_english_male-medium"
)

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

should_prepare_voice() {
    local voice_key="$1"
    if [[ -z "${PIPER_VOICE_KEYS}" ]]; then
        return 0
    fi

    local padded=",${PIPER_VOICE_KEYS},"
    [[ "${padded}" == *",${voice_key},"* ]]
}

require_cmd curl
require_cmd tar
require_cmd python3

info "Repo root: ${REPO_ROOT}"

mkdir -p "${AAR_DIR}" "${ASSETS_DIR}" "${DOWNLOADS_DIR}"

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

if ! download_if_missing "${ESPEAK_URL}" "${ESPEAK_ARCHIVE_PATH}" "espeak-ng-data archive"; then
    error "Failed to download espeak-ng-data from ${ESPEAK_URL}"
fi

READY_VOICES=()
FAILED_VOICES=()

for spec in "${VOICE_SPECS[@]}"; do
    IFS="|" read -r voice_key voice_hf_path asset_dir_name <<< "${spec}"
    if ! should_prepare_voice "${voice_key}"; then
        continue
    fi

    raw_model_name="${voice_key}.onnx"
    raw_json_name="${voice_key}.onnx.json"
    raw_model_path="${DOWNLOADS_DIR}/${raw_model_name}"
    raw_json_path="${DOWNLOADS_DIR}/${raw_json_name}"
    voice_assets_dir="${ASSETS_DIR}/${asset_dir_name}"
    espeak_dir="${voice_assets_dir}/espeak-ng-data"
    piper_onnx_url="${PIPER_BASE_URL}/${voice_hf_path}/${raw_model_name}?download=true"
    piper_json_url="${PIPER_BASE_URL}/${voice_hf_path}/${raw_json_name}?download=true"

    info "Preparing Sherpa Piper voice: ${voice_key}"
    voice_ready=true

    if ! download_if_missing "${piper_onnx_url}" "${raw_model_path}" "raw Piper ONNX model (${voice_key})"; then
        warn "Failed to download Piper ONNX model from ${piper_onnx_url}"
        voice_ready=false
    fi

    if [[ "${voice_ready}" == true ]] &&
        ! download_if_missing "${piper_json_url}" "${raw_json_path}" "raw Piper JSON config (${voice_key})"; then
        warn "Failed to download Piper JSON config from ${piper_json_url}"
        voice_ready=false
    fi

    if [[ "${voice_ready}" == true ]]; then
        rm -rf "${espeak_dir}"
        mkdir -p "${voice_assets_dir}"
        info "Extracting espeak-ng-data for ${voice_key}..."
        tar -xjf "${ESPEAK_ARCHIVE_PATH}" -C "${voice_assets_dir}"

        info "Converting raw Piper voice into Sherpa layout (${voice_key})..."
        python3 "${REPO_ROOT}/scripts/convert_piper_voice.py" \
            --voice-key "${voice_key}" \
            --input-model "${raw_model_path}" \
            --input-config "${raw_json_path}" \
            --output-dir "${voice_assets_dir}"
    fi

    all_ok=true
    if [[ "${voice_ready}" == true ]]; then
        for required in model.onnx tokens.txt espeak-ng-data; do
            if [[ ! -e "${voice_assets_dir}/${required}" ]]; then
                warn "Expected file/dir missing: ${voice_assets_dir}/${required}"
                all_ok=false
            fi
        done
    else
        all_ok=false
    fi

    if [[ "${all_ok}" == true ]]; then
        info "✓ ${voice_key} ready at ${voice_assets_dir}"
        READY_VOICES+=("${voice_key}")
    else
        warn "${voice_key} is incomplete. Android TTS will remain available as fallback."
        FAILED_VOICES+=("${voice_key}")
    fi
done

echo ""
echo "================================================================="
echo "  Sherpa-ONNX TTS spike setup complete"
echo "================================================================="
echo ""
echo "  AAR: ${AAR_PATH}"
if [[ "${#READY_VOICES[@]}" -gt 0 ]]; then
    echo "  Ready voices:"
    for voice_key in "${READY_VOICES[@]}"; do
        echo "    - ${voice_key}"
    done
else
    echo "  Ready voices: none"
fi
if [[ "${#FAILED_VOICES[@]}" -gt 0 ]]; then
    echo "  Incomplete voices:"
    for voice_key in "${FAILED_VOICES[@]}"; do
        echo "    - ${voice_key}"
    done
fi
echo ""
echo "  Notes:"
echo "    - The southern-English public Piper pack currently resolves as low quality, so"
echo "      this setup prepares en_GB-southern_english_female-low for that option."
echo "    - Android TTS remains available as the runtime fallback."
echo ""
echo "  Next steps:"
echo "    1. Rebuild the project so Gradle picks up the new AAR:"
echo "       ./gradlew :app:assembleDebug"
echo ""
echo "    2. Install on device and exercise the voice output path."
echo "       Logcat tag 'KernelAI' shows which backend is selected."
echo ""
echo "  To revert to Android TTS only (without touching code):"
echo "    - Remove or move ${AAR_PATH}"
echo "    - Rebuild — SherpaOnnxVoiceOutputController will return Unavailable"
echo "      and FallbackVoiceOutputController will route to Android TTS."
echo "================================================================="
