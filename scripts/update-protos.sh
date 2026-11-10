#!/bin/bash
set -euo pipefail

# Schemas shipped by firmware v2.8.2.d5f1619; do not follow unreleased master.
PROTO_VERSION="8d0ed0c135f91ad1d6f9c60bc341277d64946bce"
FIRMWARE_VERSION="v2.8.2.d5f1619"
REPO_URL="https://raw.githubusercontent.com/meshtastic/protobufs/${PROTO_VERSION}"
cd "$(dirname "$0")/.."

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/meshtastic"

PROTO_FILES=(
    "admin.proto"
    "apponly.proto"
    "atak.proto"
    "cannedmessages.proto"
    "channel.proto"
    "clientonly.proto"
    "config.proto"
    "connection_status.proto"
    "device_ui.proto"
    "deviceonly.proto"
    "deviceonly_legacy.proto"
    "field_metadata.proto"
    "interdevice.proto"
    "localonly.proto"
    "lorawan_bridge.proto"
    "mesh.proto"
    "mesh_beacon.proto"
    "module_config.proto"
    "mqtt.proto"
    "paxcount.proto"
    "portnums.proto"
    "powermon.proto"
    "remote_hardware.proto"
    "rtttl.proto"
    "serial_hal.proto"
    "storeforward.proto"
    "telemetry.proto"
    "xmodem.proto"
)

printf 'Downloading Meshtastic schemas for %s (%s)...\n' "$FIRMWARE_VERSION" "$PROTO_VERSION"
# Stage every file first: a failed download must never delete a working schema.
for filename in "${PROTO_FILES[@]}"; do
    curl --fail --silent --show-error --location --retry 2 \
        "$REPO_URL/meshtastic/$filename" -o "$stage/meshtastic/$filename"
done
curl --fail --silent --show-error --location --retry 2 \
    "$REPO_URL/nanopb.proto" -o "$stage/nanopb.proto"

# Retain only the aliases and traffic settings needed for pre-2.8 radios.
patch --batch --forward -p1 -d "$stage" < scripts/meshtastic-legacy-compat.patch
mkdir -p src/main/proto/meshtastic
cp "$stage/meshtastic/"*.proto src/main/proto/meshtastic/
cp "$stage/nanopb.proto" src/main/proto/nanopb.proto
printf 'firmware=%s\nprotobufs.commit=%s\n' "$FIRMWARE_VERSION" "$PROTO_VERSION" \
    > src/main/proto/meshtastic-version.properties
printf 'Updated %s schemas. Run ./gradlew generateProto to regenerate Java classes.\n' "${#PROTO_FILES[@]}"
