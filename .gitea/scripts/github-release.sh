#!/usr/bin/env bash
set -euo pipefail

require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "::error::${name} is required for GitHub release publishing"
    exit 1
  fi
}

github_api() {
  curl -sf \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$@"
}

gitea_api() {
  curl -sf \
    -H "Authorization: token ${GITEA_TOKEN}" \
    "$@"
}

github_repo_api() {
  printf 'https://api.github.com/repos/%s' "${GH_REPOSITORY}"
}

gitea_repo_api() {
  local server="${GITEA_SERVER_URL:-${GITHUB_SERVER_URL:-}}"
  local repo="${GITEA_REPOSITORY:-${GITHUB_REPOSITORY:-}}"

  if [ -z "${server}" ] || [ -z "${repo}" ]; then
    echo "::error::GITEA_SERVER_URL/GITHUB_SERVER_URL and GITEA_REPOSITORY/GITHUB_REPOSITORY are required"
    exit 1
  fi

  printf '%s/api/v1/repos/%s' "${server%/}" "${repo}"
}

github_upload_api() {
  printf 'https://uploads.github.com/repos/%s' "${GH_REPOSITORY}"
}

ensure_github_tag() {
  local tag="$1"
  local remote_url="https://x-access-token:${GH_TOKEN}@github.com/${GH_REPOSITORY}.git"

  if git ls-remote --exit-code --tags "${remote_url}" "refs/tags/${tag}" >/dev/null 2>&1; then
    return
  fi

  git push --quiet "${remote_url}" "refs/tags/${tag}:refs/tags/${tag}"
}

get_release_id() {
  local tag="$1"
  github_api "$(github_repo_api)/releases/tags/${tag}" | jq -r '.id'
}

ensure_release() {
  local tag="$1"
  local name="$2"
  local prerelease="$3"
  local notes_file="$4"
  local response_file status release_id create_payload update_payload

  ensure_github_tag "${tag}"

  create_payload="$(jq -n \
    --arg tag "${tag}" \
    --arg name "${name}" \
    --rawfile body "${notes_file}" \
    --argjson prerelease "${prerelease}" \
    '{tag_name:$tag,name:$name,body:$body,draft:true,prerelease:$prerelease}')"
  update_payload="$(jq -n \
    --arg name "${name}" \
    --rawfile body "${notes_file}" \
    --argjson prerelease "${prerelease}" \
    '{name:$name,body:$body,draft:true,prerelease:$prerelease}')"

  response_file="$(mktemp)"
  status="$(curl -sS -o "${response_file}" -w '%{http_code}' \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$(github_repo_api)/releases/tags/${tag}")"

  if [ "${status}" = "404" ]; then
    github_api -X POST \
      -H "Content-Type: application/json" \
      -d "${create_payload}" \
      "$(github_repo_api)/releases" >/dev/null
  elif [ "${status}" -ge 200 ] && [ "${status}" -lt 300 ]; then
    release_id="$(jq -r '.id' "${response_file}")"
    github_api -X PATCH \
      -H "Content-Type: application/json" \
      -d "${update_payload}" \
      "$(github_repo_api)/releases/${release_id}" >/dev/null
  else
    echo "::error::GitHub release lookup failed with HTTP ${status}"
    cat "${response_file}"
    exit 1
  fi

  rm -f "${response_file}"
}

upload_assets() {
  local tag="$1"
  shift

  local release_id file name encoded_name asset_id
  release_id="$(get_release_id "${tag}")"

  for file in "$@"; do
    [ -f "${file}" ] || continue

    name="$(basename "${file}")"
    encoded_name="$(jq -rn --arg value "${name}" '$value|@uri')"
    asset_id="$(github_api "$(github_repo_api)/releases/${release_id}/assets?per_page=100" \
      | jq -r --arg name "${name}" 'first(.[] | select(.name == $name) | .id) // empty')"

    if [ -n "${asset_id}" ]; then
      github_api -X DELETE "$(github_repo_api)/releases/assets/${asset_id}" >/dev/null
    fi

    echo "Uploading ${name} to GitHub Release..."
    curl -sf -X POST \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      -H "Content-Type: application/octet-stream" \
      --data-binary "@${file}" \
      "$(github_upload_api)/releases/${release_id}/assets?name=${encoded_name}" >/dev/null
  done
}

publish_release() {
  local tag="$1"
  local release_id
  release_id="$(get_release_id "${tag}")"
  github_api -X PATCH \
    -H "Content-Type: application/json" \
    -d '{"draft":false}' \
    "$(github_repo_api)/releases/${release_id}" >/dev/null
}

download_gitea_assets() {
  local release_id="$1"
  local output_dir="$2"
  local assets_json count asset name url

  mkdir -p "${output_dir}"
  assets_json="$(gitea_api "$(gitea_repo_api)/releases/${release_id}/assets?limit=100")"
  count="$(printf '%s' "${assets_json}" | jq 'length')"

  if [ "${count}" -eq 0 ]; then
    echo "::error::Gitea release has no assets to publish"
    exit 1
  fi

  while IFS= read -r asset; do
    name="$(printf '%s' "${asset}" | base64 --decode | jq -r '.name')"
    url="$(printf '%s' "${asset}" | base64 --decode | jq -r '.browser_download_url')"

    case "${name}" in
      ""|null|.|..|*/*)
        echo "::error::Invalid Gitea release asset name: ${name}"
        exit 1
        ;;
    esac

    if [ -z "${url}" ] || [ "${url}" = "null" ]; then
      echo "::error::Gitea release asset ${name} has no browser_download_url"
      exit 1
    fi

    echo "Downloading ${name} from Gitea Release..."
    curl -sfL \
      -H "Authorization: token ${GITEA_TOKEN}" \
      -o "${output_dir}/${name}" \
      "${url}"
  done < <(printf '%s' "${assets_json}" | jq -r '.[] | @base64')
}

publish_from_gitea() {
  local tag="$1"
  local release_json release_id name prerelease tmp_dir notes_file assets_dir

  release_json="$(gitea_api "$(gitea_repo_api)/releases/tags/${tag}")"
  release_id="$(printf '%s' "${release_json}" | jq -r '.id')"
  name="$(printf '%s' "${release_json}" | jq -r --arg tag "${tag}" '(.name // "") as $name | if $name == "" then $tag else $name end')"
  prerelease="$(printf '%s' "${release_json}" | jq -r '.prerelease // false')"

  if [ -z "${release_id}" ] || [ "${release_id}" = "null" ]; then
    echo "::error::Gitea release not found for tag ${tag}"
    exit 1
  fi

  tmp_dir="$(mktemp -d)"
  notes_file="${tmp_dir}/release-notes.md"
  assets_dir="${tmp_dir}/assets"
  printf '%s' "${release_json}" | jq -r '.body // ""' > "${notes_file}"

  ensure_release "${tag}" "${name}" "${prerelease}" "${notes_file}"
  download_gitea_assets "${release_id}" "${assets_dir}"
  upload_assets "${tag}" "${assets_dir}"/*
  publish_release "${tag}"
}

require_env GH_TOKEN
require_env GH_REPOSITORY

command="${1:-}"
shift || true

case "${command}" in
  ensure-release)
    ensure_release "$@"
    ;;
  upload-assets)
    upload_assets "$@"
    ;;
  publish-release)
    publish_release "$@"
    ;;
  publish-from-gitea)
    require_env GITEA_TOKEN
    publish_from_gitea "$@"
    ;;
  *)
    echo "usage: $0 ensure-release|upload-assets|publish-release|publish-from-gitea ..."
    exit 2
    ;;
esac
