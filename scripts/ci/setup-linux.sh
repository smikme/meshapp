#!/usr/bin/env bash
set -euo pipefail
# AppImage assets and OSTree commits are architecture-specific. Both Builder
# commits below come from the same Flathub source revision (86fbd562cf7b).
arch="$(uname -m)"
case "$arch" in
  x86_64)
    appimagetool_sha256=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
    builder_commit=50461ee00afa8ae6b133762447dcff6348781568ebebaf0293eed8242f79f20a
    ;;
  aarch64|arm64)
    arch=aarch64
    appimagetool_sha256=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158
    builder_commit=407fe008ab96d6b9e199d01c95523bef08341090be6e300f2717d7858890db57
    ;;
  *)
    printf 'Unsupported Linux packaging architecture: %s\n' "$arch" >&2
    exit 1
    ;;
esac

sudo apt-get update
sudo apt-get install -y build-essential cmake pkg-config libsystemd-dev fakeroot \
  libgtk-3-0 libasound2 xvfb flatpak dbus-daemon
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install -y flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
# Ubuntu 22.04 ships Builder 1.2, whose appstream-compose was removed from
# recent SDKs. Use the official Builder with its matching AppStream tooling.
flatpak --user install -y flathub org.flatpak.Builder
flatpak --user update -y --commit="$builder_commit" org.flatpak.Builder
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
  "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-${arch}.AppImage" \
  --output "$tool"
printf '%s  %s\n' "$appimagetool_sha256" "$tool" | sha256sum --check
chmod +x "$tool"
