#!/usr/bin/env bash
set -euo pipefail

trim() {
  sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

event_input() {
  local name="$1"
  jq -r --arg name "${name}" '.inputs[$name] // empty' "${GITHUB_EVENT_PATH}" 2>/dev/null | trim
}

write_env_value() {
  local name="$1"
  local value="$2"
  printf '%s=%s\n' "${name}" "${value}" >> "${GITHUB_ENV}"
}

write_env_multiline() {
  local name="$1"
  local value="$2"
  local marker="MESHAPP_${name}_EOF_$$"
  {
    printf '%s<<%s\n' "${name}" "${marker}"
    printf '%s\n' "${value}"
    printf '%s\n' "${marker}"
  } >> "${GITHUB_ENV}"
}

version="$(event_input release_tag)"
if [ -z "${version}" ]; then
  version="$(event_input version)"
fi
release_notes="$(event_input release_notes)"
release_notes_ru="$(event_input release_notes_ru)"

if [ -z "${version}" ]; then
  echo "::error::Set workflow_dispatch input 'release_tag', for example v2.1.20" >&2
  exit 1
fi

case "${version}" in
  refs/tags/*)
    version="${version#refs/tags/}"
    ;;
esac

case "${version}" in
  v*)
    release_tag="${version}"
    ;;
  *)
    release_tag="v${version}"
    ;;
esac

case "${release_tag}" in
  *[!A-Za-z0-9._-]*)
    echo "::error::Invalid release version '${release_tag}'. Use a tag-like value, for example v2.1.20" >&2
    exit 1
    ;;
esac

write_env_value RELEASE_TAG "${release_tag}"
write_env_value MESHAPP_VERSION "${release_tag}"

if [ -n "${release_notes}" ]; then
  write_env_multiline MESHAPP_RELEASE_NOTES "${release_notes}"
fi

if [ -n "${release_notes_ru}" ]; then
  write_env_multiline MESHAPP_RELEASE_NOTES_RU "${release_notes_ru}"
fi

echo "Release version: ${release_tag}"
