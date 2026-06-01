#!/bin/bash
# Runs MeshApp on macOS with Dock and Cmd+Tab visibility.
# Thin wrapper around the Gradle runMac task.

set -e
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$PROJECT_DIR/gradlew" runMac "$@"
