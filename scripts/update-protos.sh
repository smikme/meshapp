#!/bin/bash
set -euo pipefail

# Meshtastic protobufs version
PROTO_VERSION="master"
REPO_URL="https://raw.githubusercontent.com/meshtastic/protobufs/${PROTO_VERSION}"

PROTO_DIR="src/main/proto/meshtastic"
NANOPB_URL="https://raw.githubusercontent.com/nanopb/nanopb/master/generator/proto/nanopb.proto"

# Navigate to project root
cd "$(dirname "$0")/.."

echo "Downloading Meshtastic protobufs ${PROTO_VERSION}..."

mkdir -p "$PROTO_DIR"

# List of proto files from meshtastic/protobufs
PROTO_FILES=(
    "meshtastic/admin.proto"
    "meshtastic/apponly.proto"
    "meshtastic/atak.proto"
    "meshtastic/cannedmessages.proto"
    "meshtastic/channel.proto"
    "meshtastic/clientonly.proto"
    "meshtastic/config.proto"
    "meshtastic/connection_status.proto"
    "meshtastic/device_ui.proto"
    "meshtastic/deviceonly.proto"
    "meshtastic/localonly.proto"
    "meshtastic/mesh.proto"
    "meshtastic/module_config.proto"
    "meshtastic/mqtt.proto"
    "meshtastic/paxcount.proto"
    "meshtastic/portnums.proto"
    "meshtastic/powermon.proto"
    "meshtastic/remote_hardware.proto"
    "meshtastic/rtttl.proto"
    "meshtastic/storeforward.proto"
    "meshtastic/telemetry.proto"
    "meshtastic/xmodem.proto"
)

for proto in "${PROTO_FILES[@]}"; do
    filename=$(basename "$proto")
    echo "  Downloading ${filename}..."
    curl -sfL "${REPO_URL}/${proto}" -o "${PROTO_DIR}/${filename}" || {
        echo "  WARNING: Failed to download ${filename}, skipping"
        rm -f "${PROTO_DIR}/${filename}"
    }
done

# Download nanopb.proto (needed by some meshtastic protos)
echo "  Downloading nanopb.proto..."
curl -sfL "$NANOPB_URL" -o "src/main/proto/nanopb.proto"

echo ""
echo "Downloaded proto files:"
ls -1 "$PROTO_DIR"/*.proto 2>/dev/null | wc -l | xargs printf "  %s meshtastic proto files\n"
ls -1 src/main/proto/nanopb.proto 2>/dev/null && echo "  nanopb.proto"
echo ""
echo "Done! Run './gradlew generateProto' to generate Java classes."
