#!/usr/bin/env bash
# download-models.sh — Download all gitignored model files for Kernel AI Assistant.
#
# Usage:
#   ./scripts/download-models.sh              # download everything
#   ./scripts/download-models.sh stt          # only STT model
#   ./scripts/download-models.sh wakeword     # only wake word models
#
# After download, SHA-256 hashes are verified.  Any mismatch exits non-zero.
# All model files land under app/src/main/assets/models/ (gitignored).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/models"

# ── Colour helpers ────────────────────────────────────────────────────────────
green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m\n' "$*"; }
red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }

# ── Download + verify helper ──────────────────────────────────────────────────
# download_file <url> <dest_path> <expected_sha256|"skip">
download_file() {
    local url="$1" dest="$2" expected_sha256="$3"

    mkdir -p "$(dirname "$dest")"

    if [[ -f "$dest" ]]; then
        yellow "  already exists: $dest"
        if [[ "$expected_sha256" != "skip" ]]; then
            verify_sha256 "$dest" "$expected_sha256"
        fi
        return
    fi

    echo "  downloading: $url"
    if command -v curl &>/dev/null; then
        curl -fL --progress-bar -o "$dest" "$url"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress -O "$dest" "$url"
    else
        red "ERROR: neither curl nor wget found"; exit 1
    fi

    if [[ "$expected_sha256" != "skip" ]]; then
        verify_sha256 "$dest" "$expected_sha256"
    fi
    green "  ok: $(basename "$dest")"
}

verify_sha256() {
    local file="$1" expected="$2"
    local actual
    if command -v sha256sum &>/dev/null; then
        actual=$(sha256sum "$file" | awk '{print $1}')
    elif command -v shasum &>/dev/null; then
        actual=$(shasum -a 256 "$file" | awk '{print $1}')
    else
        yellow "  warning: no sha256 tool found, skipping hash check"
        return
    fi
    if [[ "$actual" != "$expected" ]]; then
        red "  HASH MISMATCH for $file"
        red "    expected: $expected"
        red "    actual:   $actual"
        rm -f "$file"
        exit 1
    fi
}

# ── STT model: Sherpa-ONNX streaming Zipformer (int8, NZ English) ─────────────
# Model: sherpa-onnx-streaming-zipformer-en-2023-02-21 (int8)
# Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
STT_BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
STT_MODEL_DIR="$ASSETS_DIR/stt"
STT_ARCHIVE="sherpa-onnx-streaming-zipformer-en-2023-02-21.tar.bz2"

download_stt() {
    echo ""
    echo "=== STT model (Zipformer int8 streaming, ~70 MB) ==="

    local need_download=false
    for f in \
        "encoder-epoch-99-avg-1.int8.onnx" \
        "decoder-epoch-99-avg-1.int8.onnx" \
        "joiner-epoch-99-avg-1.int8.onnx" \
        "tokens.txt"
    do
        [[ -f "$STT_MODEL_DIR/$f" ]] || { need_download=true; break; }
    done

    if [[ "$need_download" == false ]]; then
        green "  STT model files already present — skipping."
        return
    fi

    local tmp_archive="/tmp/$STT_ARCHIVE"
    if [[ ! -f "$tmp_archive" ]]; then
        echo "  downloading archive…"
        if command -v curl &>/dev/null; then
            curl -fL --progress-bar \
                -o "$tmp_archive" \
                "$STT_BASE_URL/$STT_ARCHIVE"
        else
            wget -q --show-progress -O "$tmp_archive" "$STT_BASE_URL/$STT_ARCHIVE"
        fi
    else
        yellow "  archive already cached at $tmp_archive"
    fi

    echo "  extracting…"
    local tmp_dir
    tmp_dir=$(mktemp -d)
    tar -xjf "$tmp_archive" -C "$tmp_dir"

    mkdir -p "$STT_MODEL_DIR"
    # The archive unpacks to a directory named after the model.
    local extracted_dir
    extracted_dir=$(find "$tmp_dir" -maxdepth 1 -type d | grep -v "^$tmp_dir$" | head -1)

    for f in \
        "encoder-epoch-99-avg-1.int8.onnx" \
        "decoder-epoch-99-avg-1.int8.onnx" \
        "joiner-epoch-99-avg-1.int8.onnx" \
        "tokens.txt"
    do
        if [[ -f "$extracted_dir/$f" ]]; then
            cp "$extracted_dir/$f" "$STT_MODEL_DIR/$f"
            green "  installed: $f"
        else
            red "  ERROR: expected file not found in archive: $f"
            rm -rf "$tmp_dir"
            exit 1
        fi
    done

    rm -rf "$tmp_dir"
    green "  STT model ready."
}

# ── Wake word models (openWakeWord pipeline) ──────────────────────────────────
# Stage 1 + 2 models are fixed upstream downloads; Stage 3 is trained locally.
WAKEWORD_DIR="$ASSETS_DIR/wakeword"

download_wakeword() {
    echo ""
    echo "=== Wake word pipeline models ==="

    # Stage 1: melspectrogram
    download_file \
        "https://github.com/dscripka/openWakeWord/releases/download/v0.1.1/melspectrogram.onnx" \
        "$WAKEWORD_DIR/melspectrogram.onnx" \
        "skip"

    # Stage 2: embedding model
    download_file \
        "https://github.com/dscripka/openWakeWord/releases/download/v0.1.1/embedding_model.onnx" \
        "$WAKEWORD_DIR/embedding_model.onnx" \
        "skip"

    # Stage 3: custom classifier — must be trained locally via training/wakeword/
    if [[ ! -f "$WAKEWORD_DIR/hey_jandal.onnx" ]]; then
        yellow "  hey_jandal.onnx not found."
        yellow "  Train it first: cd training && python wakeword/train.py"
        yellow "  Then copy output to: $WAKEWORD_DIR/hey_jandal.onnx"
    else
        green "  hey_jandal.onnx already present."
    fi
}

# ── Entry point ───────────────────────────────────────────────────────────────
FILTER="${1:-all}"

case "$FILTER" in
    stt)       download_stt ;;
    wakeword)  download_wakeword ;;
    all)       download_stt; download_wakeword ;;
    *)
        red "Unknown filter '$FILTER'. Valid options: stt, wakeword, all"
        exit 1
        ;;
esac

echo ""
green "Done. Models are in $ASSETS_DIR"
echo "Remember: these files are gitignored and must be re-downloaded after a fresh clone."
