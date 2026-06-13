#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <tag> <output-json> [assets-dir]" >&2
  exit 2
}

require_command() {
  local name="$1"
  if ! command -v "${name}" >/dev/null 2>&1; then
    echo "::error::${name} is required" >&2
    exit 1
  fi
}

gitea_api() {
  curl -sf \
    -H "Authorization: token ${GITEA_TOKEN}" \
    "$@"
}

gitea_repo_api() {
  local server="${GITEA_SERVER_URL:-${GITHUB_SERVER_URL:-}}"
  local repo="${GITEA_REPOSITORY:-${GITHUB_REPOSITORY:-}}"

  if [ -z "${server}" ] || [ -z "${repo}" ]; then
    echo "::error::GITEA_SERVER_URL/GITHUB_SERVER_URL and GITEA_REPOSITORY/GITHUB_REPOSITORY are required" >&2
    exit 1
  fi

  printf '%s/api/v1/repos/%s' "${server%/}" "${repo}"
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  else
    shasum -a 256 "${file}" | awk '{print $1}'
  fi
}

file_size() {
  local file="$1"
  if stat -c '%s' "${file}" >/dev/null 2>&1; then
    stat -c '%s' "${file}"
  else
    stat -f '%z' "${file}"
  fi
}

asset_url() {
  local name="$1"
  local fallback_url="${2:-}"
  local base_url="${MESHAPP_UPDATE_ASSET_BASE_URL:-}"

  if [ -n "${base_url}" ]; then
    printf '%s/%s' "${base_url%/}" "${name}"
    return
  fi

  if [ -n "${fallback_url}" ]; then
    printf '%s' "${fallback_url}"
    return
  fi

  printf '%s' "${name}"
}

write_update_private_key() {
  local target="$1"
  local key="${MESHAPP_UPDATE_ED25519_PRIVATE_KEY:-}"

  if [ -z "${key}" ]; then
    return 1
  fi

  if printf '%s' "${key}" | grep -q 'BEGIN .*PRIVATE KEY'; then
    printf '%s\n' "${key}" > "${target}"
  else
    if ! printf '%s' "${key}" | base64 --decode > "${target}" 2>/dev/null; then
      printf '%s' "${key}" | base64 -D > "${target}"
    fi
  fi
  chmod 600 "${target}"
}

sign_self_update_payload() {
  local key_file="$1"
  local payload_file="$2"

  openssl pkeyutl -sign -rawin -inkey "${key_file}" -in "${payload_file}" \
    | base64 | tr -d '\n'
}

detect_self_update_key() {
  local name="$1"
  local stem="${name%.zip}"
  stem="${stem%-selfupdate}"

  case "${stem}" in
    *-windows-x86_64) printf 'windows-x86_64' ;;
    *-windows-aarch64) printf 'windows-aarch64' ;;
    *-macos-x86_64) printf 'macos-x86_64' ;;
    *-macos-aarch64) printf 'macos-aarch64' ;;
    *-linux-x86_64) printf 'linux-x86_64' ;;
    *-linux-aarch64) printf 'linux-aarch64' ;;
    *) printf '' ;;
  esac
}

normalize_release_version() {
  local value="$1"
  value="${value#v}"
  printf '%s' "${value}"
}

detect_asset_version() {
  local name="$1"
  local stem

  case "${name}" in
    meshapp_*_*.deb)
      stem="${name#meshapp_}"
      printf '%s' "${stem%%_*}"
      ;;
    MeshApp-*.dmg)
      stem="${name#MeshApp-}"
      printf '%s' "${stem%.dmg}"
      ;;
    MeshApp-*.msi)
      stem="${name#MeshApp-}"
      printf '%s' "${stem%.msi}"
      ;;
    MeshApp-*.AppImage)
      stem="${name#MeshApp-}"
      stem="${stem%.AppImage}"
      case "${stem}" in
        *-x86_64) printf '%s' "${stem%-x86_64}" ;;
        *-aarch64) printf '%s' "${stem%-aarch64}" ;;
        *) printf '' ;;
      esac
      ;;
    MeshApp-*.flatpak)
      stem="${name#MeshApp-}"
      stem="${stem%.flatpak}"
      case "${stem}" in
        *-x86_64) printf '%s' "${stem%-x86_64}" ;;
        *-aarch64) printf '%s' "${stem%-aarch64}" ;;
        *) printf '' ;;
      esac
      ;;
    MeshApp-*-selfupdate.zip)
      stem="${name#MeshApp-}"
      stem="${stem%-selfupdate.zip}"
      case "${stem}" in
        *-windows-x86_64) printf '%s' "${stem%-windows-x86_64}" ;;
        *-windows-aarch64) printf '%s' "${stem%-windows-aarch64}" ;;
        *-macos-x86_64) printf '%s' "${stem%-macos-x86_64}" ;;
        *-macos-aarch64) printf '%s' "${stem%-macos-aarch64}" ;;
        *-linux-x86_64) printf '%s' "${stem%-linux-x86_64}" ;;
        *-linux-aarch64) printf '%s' "${stem%-linux-aarch64}" ;;
        *) printf '' ;;
      esac
      ;;
    *)
      printf ''
      ;;
  esac
}

assert_asset_version_matches_release() {
  local name="$1"
  local release_version="$2"
  local asset_version

  asset_version="$(detect_asset_version "${name}")"
  if [ -z "${asset_version}" ]; then
    return
  fi
  if [ "$(normalize_release_version "${asset_version}")" != "$(normalize_release_version "${release_version}")" ]; then
    echo "::error::Asset ${name} belongs to version ${asset_version}, but selected release version is ${release_version}" >&2
    exit 1
  fi
}

add_download_url() {
  local manifest="$1"
  local key="$2"
  local url="$3"
  jq --arg key "${key}" --arg url "${url}" '.downloads[$key] = $url' <<< "${manifest}"
}

add_self_update_artifact() {
  local manifest="$1"
  local key="$2"
  local version="$3"
  local url="$4"
  local sha256="$5"
  local size="$6"
  local signature="$7"

  jq \
    --arg key "${key}" \
    --arg version "${version}" \
    --arg url "${url}" \
    --arg sha256 "${sha256}" \
    --argjson size "${size}" \
    --arg signature "${signature}" \
    '.selfUpdate[$key] = {
      type: "full-archive",
      format: "zip",
      version: $version,
      url: $url,
      sha256: $sha256,
      size: $size
    }
    | if $signature == "" then . else .selfUpdate[$key].signature = $signature end' \
    <<< "${manifest}"
}

download_gitea_assets() {
  local tag="$1"
  local output_dir="$2"
  local metadata_file="$3"
  local release_id assets_json

  if [ -z "${GITEA_TOKEN:-}" ]; then
    echo "::error::GITEA_TOKEN is required when assets-dir is not provided" >&2
    exit 1
  fi

  release_id="$(gitea_api "$(gitea_repo_api)/releases/tags/${tag}" | jq -r '.id')"
  if [ -z "${release_id}" ] || [ "${release_id}" = "null" ]; then
    echo "::error::Gitea release not found for tag ${tag}" >&2
    exit 1
  fi

  mkdir -p "${output_dir}"
  assets_json="$(gitea_api "$(gitea_repo_api)/releases/${release_id}/assets?limit=100")"
  printf '%s' "${assets_json}" > "${metadata_file}"

  while IFS= read -r asset; do
    local name url
    name="$(printf '%s' "${asset}" | base64 --decode | jq -r '.name')"
    url="$(printf '%s' "${asset}" | base64 --decode | jq -r '.browser_download_url')"
    case "${name}" in
      ""|null|.|..|*/*|meshapp.json)
        continue
        ;;
    esac
    echo "Downloading ${name}..."
    curl -sfL \
      -H "Authorization: token ${GITEA_TOKEN}" \
      -o "${output_dir}/${name}" \
      "${url}"
  done < <(printf '%s' "${assets_json}" | jq -r '.[] | @base64')
}

metadata_url_for() {
  local metadata_file="$1"
  local name="$2"

  if [ ! -s "${metadata_file}" ]; then
    return
  fi
  jq -r --arg name "${name}" \
    'first(.[] | select(.name == $name) | .browser_download_url) // empty' \
    "${metadata_file}"
}

tag="${1:-}"
output_json="${2:-}"
assets_dir="${3:-}"

if [ -z "${tag}" ] || [ -z "${output_json}" ]; then
  usage
fi

require_command jq
require_command curl
require_command openssl

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

metadata_file="${work_dir}/assets.json"
if [ -z "${assets_dir}" ]; then
  assets_dir="${work_dir}/assets"
  download_gitea_assets "${tag}" "${assets_dir}" "${metadata_file}"
else
  metadata_file="/dev/null"
fi

version="${MESHAPP_VERSION:-${tag}}"
version_code="${MESHAPP_VERSION_CODE:-}"
if [ -z "${version_code}" ]; then
  version_code="$(git rev-list --count HEAD 2>/dev/null || true)"
fi
if [ -z "${version_code}" ]; then
  echo "::error::Unable to resolve versionCode. Set MESHAPP_VERSION_CODE." >&2
  exit 1
fi
case "${version_code}" in
  *[!0-9]*|0)
    echo "::error::versionCode must be a positive integer" >&2
    exit 1
    ;;
esac

release_notes="${MESHAPP_RELEASE_NOTES:-}"
if [ -z "${release_notes}" ] && [ -s "${metadata_file}" ]; then
  release_notes="$(gitea_api "$(gitea_repo_api)/releases/tags/${tag}" | jq -r '.body // ""')"
fi
release_notes_ru="${MESHAPP_RELEASE_NOTES_RU:-${release_notes}}"

self_update_assets=0
for file in "${assets_dir}"/*selfupdate.zip; do
  [ -f "${file}" ] || continue
  self_update_assets=1
  break
done

private_key_file="${work_dir}/ed25519-private.pem"
signing_enabled=false
if write_update_private_key "${private_key_file}"; then
  signing_enabled=true
  echo "Self-update signatures enabled"
else
  if [ "${self_update_assets}" -eq 1 ]; then
    echo "::error::MESHAPP_UPDATE_ED25519_PRIVATE_KEY is required to publish self-update artifacts" >&2
    exit 1
  fi
  echo "::warning::MESHAPP_UPDATE_ED25519_PRIVATE_KEY is not set; no self-update artifacts will be signed"
fi

manifest="$(jq -n \
  --arg version "${version}" \
  --argjson versionCode "${version_code}" \
  --arg releaseNotes "${release_notes}" \
  --arg releaseNotesRu "${release_notes_ru}" \
  '{
    version: $version,
    versionCode: $versionCode,
    releaseNotes: $releaseNotes,
    releaseNotes_ru: $releaseNotesRu,
    downloads: {},
    selfUpdate: {}
  }')"

shopt -s nullglob
linux_deb_url=""
linux_appimage_url=""
linux_flatpak_url=""
for file in "${assets_dir}"/*; do
  [ -f "${file}" ] || continue
  name="$(basename "${file}")"
  case "${name}" in
    meshapp.json)
      continue
      ;;
  esac
  assert_asset_version_matches_release "${name}" "${version}"

  url="$(asset_url "${name}" "$(metadata_url_for "${metadata_file}" "${name}")")"
  case "${name}" in
    *.msi)
      manifest="$(add_download_url "${manifest}" "windows-msi" "${url}")"
      manifest="$(add_download_url "${manifest}" "windows" "${url}")"
      ;;
    *.dmg)
      manifest="$(add_download_url "${manifest}" "macos-dmg" "${url}")"
      manifest="$(add_download_url "${manifest}" "macos" "${url}")"
      ;;
    *.deb)
      manifest="$(add_download_url "${manifest}" "linux-deb" "${url}")"
      linux_deb_url="${url}"
      ;;
    *.AppImage)
      manifest="$(add_download_url "${manifest}" "linux-appimage" "${url}")"
      linux_appimage_url="${url}"
      ;;
    *.flatpak)
      manifest="$(add_download_url "${manifest}" "linux-flatpak" "${url}")"
      linux_flatpak_url="${url}"
      ;;
  esac

  if [[ "${name}" == *selfupdate.zip ]]; then
    key="$(detect_self_update_key "${name}")"
    if [ -n "${key}" ]; then
      sha256="$(sha256_file "${file}")"
      size="$(file_size "${file}")"
      signature=""
      if [ "${signing_enabled}" = true ]; then
        payload_file="${work_dir}/${key}.payload"
        {
          printf 'meshapp-self-update-v1\n'
          printf '%s\n' "${version}"
          printf '%s\n' "${version_code}"
          printf 'full-archive\n'
          printf 'zip\n'
          printf '%s\n' "${version}"
          printf '%s\n' "${url}"
          printf '%s\n' "${sha256}"
          printf '%s\n' "${size}"
        } > "${payload_file}"
        signature="$(sign_self_update_payload "${private_key_file}" "${payload_file}")"
      fi
      manifest="$(add_self_update_artifact "${manifest}" "${key}" "${version}" "${url}" "${sha256}" "${size}" "${signature}")"
    else
      echo "::warning::Unable to infer selfUpdate key from ${name}; skipping selfUpdate entry" >&2
    fi
  fi
done

linux_generic_url="${linux_deb_url:-${linux_appimage_url:-${linux_flatpak_url}}}"
if [ -n "${linux_generic_url}" ]; then
  manifest="$(add_download_url "${manifest}" "linux" "${linux_generic_url}")"
fi

manifest="$(jq '
  if (.downloads | length) == 0 then del(.downloads) else . end
  | if (.selfUpdate | length) == 0 then del(.selfUpdate) else . end
' <<< "${manifest}")"

mkdir -p "$(dirname "${output_json}")"
jq -S . <<< "${manifest}" > "${output_json}"
echo "Wrote ${output_json}"
