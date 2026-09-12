#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${WRAPPER_DIR}/../.." && pwd)"

echo "==> Building MjdbcmcpMenu (Swift)..."
cd "${WRAPPER_DIR}"
swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path)/MjdbcmcpMenu"

APP_DIR="${WRAPPER_DIR}/build/MjdbcmcpMenu.app"
CONTENTS_DIR="${APP_DIR}/Contents"
MACOS_DIR="${CONTENTS_DIR}/MacOS"
RESOURCES_DIR="${CONTENTS_DIR}/Resources"

echo "==> Assembling MjdbcmcpMenu.app..."
rm -rf "${APP_DIR}"
mkdir -p "${MACOS_DIR}" "${RESOURCES_DIR}"

cp "${BIN_PATH}" "${MACOS_DIR}/MjdbcmcpMenu"
chmod +x "${MACOS_DIR}/MjdbcmcpMenu"

cp "${WRAPPER_DIR}/Resources/Info.plist" "${CONTENTS_DIR}/Info.plist"

# Bundle mjdbcmcp.jar if built
JAR_CANDIDATE="$(find "${ROOT_DIR}/build/libs" -name "mjdbcmcp-*.jar" ! -name "*-plain.jar" 2>/dev/null | head -n 1 || true)"
if [[ -n "${JAR_CANDIDATE}" && -f "${JAR_CANDIDATE}" ]]; then
    echo "==> Bundling backend JAR: ${JAR_CANDIDATE}"
    cp "${JAR_CANDIDATE}" "${RESOURCES_DIR}/mjdbcmcp.jar"
else
    echo "==> Note: No backend JAR found in build/libs/. Build it with ./gradlew bootJar"
fi

echo "==> Ad-hoc signing app bundle..."
codesign --force --deep --sign - "${APP_DIR}"

echo "==> Successfully created ${APP_DIR}"
