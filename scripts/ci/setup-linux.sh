#!/usr/bin/env bash
set -euo pipefail
sudo apt-get update
sudo apt-get install -y build-essential cmake pkg-config libsystemd-dev fakeroot \
  libgtk-3-0 libasound2 xvfb flatpak dbus-daemon
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
# Ubuntu 22.04 ships Builder 1.2, whose appstream-compose was removed from
# recent SDKs. Use the official Builder with its matching AppStream tooling.
flatpak --user install -y flathub org.flatpak.Builder
flatpak --user update -y --commit=50461ee00afa8ae6b133762447dcff6348781568ebebaf0293eed8242f79f20a org.flatpak.Builder
builder="${RUNNER_TEMP:?}/flatpak-builder"
cat > "$builder" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
exec dbus-run-session -- flatpak run --user org.flatpak.Builder "$@"
SH
chmod +x "$builder"
printf 'FLATPAK_BUILDER=%s\n' "$builder" >> "${GITHUB_ENV:?}"
"$builder" --version
tool="${RUNNER_TEMP:?}/appimagetool.AppImage"
curl --fail --location --retry 3 \
  https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
  --output "$tool"
printf '%s  %s\n' ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0 "$tool" | sha256sum --check
chmod +x "$tool"
