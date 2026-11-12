#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
# Ubuntu 24.04 renamed GTK/ALSA runtime packages for the time_t transition.
# Select concrete packages; libasound2 is an ambiguous virtual package there.
runtime_packages=(libgtk-3-0 libasound2)
if apt-cache show libasound2t64 >/dev/null 2>&1; then
  runtime_packages=(libgtk-3-0t64 libasound2t64)
fi
sudo apt-get install -y build-essential cmake pkg-config libsystemd-dev \
  "${runtime_packages[@]}" libxtst6 libgl1 xvfb "$@"
