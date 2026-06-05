#!/bin/sh
set -eu

./gradlew flatpakGradleGenerator --no-configuration-cache \
  -PmeshappJavaFxPlatform=linux \
  -PmeshappProtocClassifier=linux-x86_64 \
  -PflatpakSourcesArch=x86_64 \
  "$@"

./gradlew flatpakGradleGenerator --no-configuration-cache \
  -PmeshappJavaFxPlatform=linux-aarch64 \
  -PmeshappProtocClassifier=linux-aarch_64 \
  -PflatpakSourcesArch=aarch64 \
  "$@"
