#!/bin/sh
set -eu

if [ "${1:-}" = "x86_64" ] || [ "${1:-}" = "amd64" ] || [ "${1:-}" = "aarch64" ] || [ "${1:-}" = "arm64" ]; then
  arches="$1"
  shift
else
  arches="${FLATPAK_SOURCES_ARCHES:-x86_64 aarch64}"
fi

generate_sources() {
  arch="$1"
  shift

  case "$arch" in
    x86_64|amd64)
      source_arch=x86_64
      javafx_platform=linux
      protoc_classifier=linux-x86_64
      ;;
    aarch64|arm64)
      source_arch=aarch64
      javafx_platform=linux-aarch64
      protoc_classifier=linux-aarch_64
      ;;
    *)
      echo "Unsupported Flatpak sources architecture: $arch" >&2
      exit 1
      ;;
  esac

  ./gradlew flatpakGradleGenerator --no-configuration-cache \
    -PmeshappJavaFxPlatform="$javafx_platform" \
    -PmeshappProtocClassifier="$protoc_classifier" \
    -PflatpakSourcesArch="$source_arch" \
    "$@"
}

for arch in $arches; do
  generate_sources "$arch" "$@"
done
