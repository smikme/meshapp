#!/bin/bash
# Запуск MeshApp на macOS с отображением в Dock и Cmd+Tab.
# Обёртка над Gradle-задачей runMac.

set -e
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$PROJECT_DIR/gradlew" runMac "$@"
