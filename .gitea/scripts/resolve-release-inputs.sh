#!/usr/bin/env bash
set -euo pipefail

trim() {
  sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

event_input() {
  local name="$1"
  jq -r --arg name "${name}" '.inputs[$name] // empty' "${GITHUB_EVENT_PATH:-/dev/null}" 2>/dev/null | trim || true
}

event_value() {
  local name="$1"
  jq -r --arg name "${name}" '.[$name] // empty' "${GITHUB_EVENT_PATH:-/dev/null}" 2>/dev/null | trim || true
}

selected_release_ref() {
  local ref="${GITHUB_REF:-}"
  local ref_name="${GITHUB_REF_NAME:-}"
  local ref_type="${GITHUB_REF_TYPE:-}"

  if [ -z "${ref}" ]; then
    ref="$(event_value ref)"
  fi

  if [ "${ref_type}" = "tag" ] && [ -n "${ref_name}" ]; then
    printf '%s\n' "${ref_name}"
    return
  fi

  case "${ref}" in
    refs/tags/*)
      printf '%s\n' "${ref#refs/tags/}"
      return
      ;;
    refs/heads/*|refs/pull/*)
      echo "::error::Select a release tag in 'Use workflow from'; selected ref is '${ref}'." >&2
      exit 1
      ;;
  esac

  if [ -n "${ref_type}" ] && [ "${ref_type}" != "tag" ]; then
    echo "::error::Select a release tag in 'Use workflow from'; selected ref type is '${ref_type}'." >&2
    exit 1
  fi

  if [ -n "${ref}" ]; then
    printf '%s\n' "${ref}"
    return
  fi

  if [ -n "${ref_name}" ]; then
    printf '%s\n' "${ref_name}"
  fi
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

version="$(selected_release_ref)"
release_notes="$(event_input release_notes)"
release_notes_ru="$(event_input release_notes_ru)"
version_code="$(event_input version_code)"

if [ -z "${version}" ]; then
  echo "::error::Select a release tag in 'Use workflow from', for example v2.1.20" >&2
  exit 1
fi

case "${version}" in
  refs/tags/*)
    version="${version#refs/tags/}"
    ;;
  refs/heads/*|refs/pull/*)
    echo "::error::Select a release tag in 'Use workflow from'; selected ref is '${version}'." >&2
    exit 1
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

if ! git rev-parse -q --verify "refs/tags/${release_tag}" >/dev/null 2>&1; then
  echo "::error::Selected workflow ref must be an existing release tag. Resolved '${release_tag}' from '${version}'." >&2
  exit 1
fi

write_env_value RELEASE_TAG "${release_tag}"
write_env_value MESHAPP_VERSION "${release_tag}"

if [ -n "${version_code}" ]; then
  case "${version_code}" in
    *[!0-9]*)
      echo "::error::version_code must be a positive integer" >&2
      exit 1
      ;;
    0)
      echo "::error::version_code must be a positive integer" >&2
      exit 1
      ;;
  esac
  write_env_value MESHAPP_VERSION_CODE "${version_code}"
fi

if [ -n "${release_notes}" ]; then
  write_env_multiline MESHAPP_RELEASE_NOTES "${release_notes}"
fi

if [ -n "${release_notes_ru}" ]; then
  write_env_multiline MESHAPP_RELEASE_NOTES_RU "${release_notes_ru}"
fi

echo "Release version: ${release_tag}"
if [ -n "${version_code}" ]; then
  echo "Manifest versionCode override: ${version_code}"
fi
