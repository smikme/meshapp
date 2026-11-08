#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update
sudo apt-get install -y build-essential cmake pkg-config libsystemd-dev fakeroot \
  libgtk-3-0 libasound2 xvfb flatpak flatpak-builder
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
tool="${RUNNER_TEMP:?}/appimagetool.AppImage"
curl --fail --location --retry 3 \
  https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
  --output "$tool"
printf '%s  %s\n' ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0 "$tool" | sha256sum --check
chmod +x "$tool"
