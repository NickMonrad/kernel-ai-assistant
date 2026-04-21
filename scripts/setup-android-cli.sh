#!/usr/bin/env bash
set -euo pipefail

DOCS_URL="https://developer.android.com/tools/agents/android-cli"
DOWNLOAD_URL="https://developer.android.com/tools/agents"
BIN_DIR="${HOME}/.local/bin"

if ! command -v android >/dev/null 2>&1; then
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  case "${OS}:${ARCH}" in
    Linux:x86_64)
      URL_OS="linux_x86_64"
      ;;
    Darwin:arm64)
      URL_OS="darwin_arm64"
      ;;
    *)
      echo "Android CLI is not installed."
      echo "Unsupported platform for automatic install: ${OS} ${ARCH}"
      echo "Download page: ${DOWNLOAD_URL}"
      exit 1
      ;;
  esac

  mkdir -p "${BIN_DIR}"
  echo "Installing official Android CLI to ${BIN_DIR}..."
  curl -fsSL "https://dl.google.com/android/cli/latest/${URL_OS}/android" -o "${BIN_DIR}/android"
  chmod +x "${BIN_DIR}/android"
  export PATH="${BIN_DIR}:${PATH}"
fi

echo "Updating Android CLI..."
android update

echo "Installing the base android-cli agent skill for detected agents..."
android init

echo "Listing installed Android skills..."
android skills list --long

cat <<EOF

Android CLI is ready.

Next steps:
  1. android describe --project_dir=$(pwd)
  2. android docs search 'your Android question'
  3. android layout --pretty              # requires connected device/emulator

Detected agents such as OpenCode and Copilot should now have the official
android-cli skill installed automatically.

Docs: ${DOCS_URL}
EOF
