#!/usr/bin/env bash
set -euo pipefail

APP_ID="${FLATPAK_APP_ID:-app.privatepractice.meshapp}"
BRANCH="${FLATPAK_BRANCH:-stable}"
PUBLIC_BASE_URL="${FLATPAK_PUBLIC_BASE_URL:-https://flatpak.privatepractice.app}"
REMOTE_NAME="${FLATPAK_REMOTE_NAME:-meshapp}"
HOMEPAGE="${FLATPAK_HOMEPAGE:-https://meshapp.ru}"
REPO_DIR="${FLATPAK_REPO_DIR:-build/flatpak/repo}"
FLATPAKREF_PATH="${FLATPAK_FLATPAKREF_PATH:-build/flatpak/${APP_ID}.flatpakref}"
DEPLOY_SSH_PORT="${FLATPAK_DEPLOY_SSH_PORT:-22}"

require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "::error::${name} is required for Flatpak repository publishing"
    exit 1
  fi
}

base64_no_wrap() {
  if base64 --help 2>/dev/null | grep -q -- '-w'; then
    base64 -w0
  else
    base64 | tr -d '\n'
  fi
}

decode_base64_file() {
  local input_file="$1"

  if base64 --decode "${input_file}" 2>/dev/null; then
    return
  fi

  base64 -d "${input_file}"
}

shell_quote() {
  local value="$1"
  printf "'%s'" "$(printf '%s' "${value}" | sed "s/'/'\\\\''/g")"
}

setup_gnupg() {
  mkdir -p "${GNUPGHOME:-${HOME}/.gnupg}"
  chmod 700 "${GNUPGHOME:-${HOME}/.gnupg}"
}

import_gpg_key() {
  require_env FLATPAK_GPG_PRIVATE_KEY_BASE64
  require_env FLATPAK_GPG_KEY_ID

  setup_gnupg

  local key_file
  key_file="$(mktemp)"
  printf '%s' "${FLATPAK_GPG_PRIVATE_KEY_BASE64}" > "${key_file}"
  decode_base64_file "${key_file}" | gpg --batch --import
  rm -f "${key_file}"

  if ! gpg --batch --with-colons --list-secret-keys "${FLATPAK_GPG_KEY_ID}" | grep -q '^sec'; then
    echo "::error::Imported Flatpak GPG key does not contain secret key ${FLATPAK_GPG_KEY_ID}"
    exit 1
  fi
}

resolve_flatpak_arch() {
  if [ -n "${FLATPAK_ARCH:-}" ]; then
    printf '%s\n' "${FLATPAK_ARCH}"
    return
  fi

  flatpak --default-arch
}

sign_repo_refs() {
  require_env FLATPAK_GPG_KEY_ID

  if [ ! -d "${REPO_DIR}" ]; then
    echo "::error::Flatpak repository directory not found: ${REPO_DIR}"
    exit 1
  fi

  local arch debug_id
  arch="$(resolve_flatpak_arch)"
  debug_id="${APP_ID}.Debug"

  flatpak build-sign \
    --gpg-sign="${FLATPAK_GPG_KEY_ID}" \
    --arch="${arch}" \
    "${REPO_DIR}" \
    "${APP_ID}" \
    "${BRANCH}"

  if [ -f "${REPO_DIR}/refs/heads/runtime/${debug_id}/${arch}/${BRANCH}" ]; then
    flatpak build-sign \
      --runtime \
      --gpg-sign="${FLATPAK_GPG_KEY_ID}" \
      --arch="${arch}" \
      "${REPO_DIR}" \
      "${debug_id}" \
      "${BRANCH}"
  fi
}

write_flatpakref() {
  require_env FLATPAK_GPG_KEY_ID

  local public_key repo_url
  public_key="$(gpg --batch --export "${FLATPAK_GPG_KEY_ID}" | base64_no_wrap)"
  repo_url="${PUBLIC_BASE_URL%/}/repo/"

  mkdir -p "$(dirname "${FLATPAKREF_PATH}")"
  cat > "${FLATPAKREF_PATH}" <<EOF
[Flatpak Ref]
Title=MeshApp
Name=${APP_ID}
Branch=${BRANCH}
Url=${repo_url}
SuggestRemoteName=${REMOTE_NAME}
Homepage=${HOMEPAGE}
RuntimeRepo=https://flathub.org/repo/flathub.flatpakrepo
IsRuntime=false
GPGKey=${public_key}
EOF
}

update_repo_summary() {
  require_env FLATPAK_GPG_KEY_ID

  if [ ! -d "${REPO_DIR}" ]; then
    echo "::error::Flatpak repository directory not found: ${REPO_DIR}"
    exit 1
  fi

  flatpak build-update-repo \
    --gpg-sign="${FLATPAK_GPG_KEY_ID}" \
    --generate-static-deltas \
    --title="MeshApp" \
    --comment="MeshApp Flatpak repository" \
    --homepage="${HOMEPAGE}" \
    "${REPO_DIR}"

  if [ ! -f "${REPO_DIR}/summary" ] || [ ! -f "${REPO_DIR}/summary.sig" ]; then
    echo "::error::Flatpak repository summary was not signed"
    exit 1
  fi
}

setup_ssh() {
  require_env FLATPAK_DEPLOY_SSH_KEY
  require_env FLATPAK_DEPLOY_HOST

  if ! command -v ssh >/dev/null 2>&1; then
    echo "::error::ssh is required for Flatpak repository publishing"
    exit 1
  fi

  if ! command -v ssh-keyscan >/dev/null 2>&1; then
    echo "::error::ssh-keyscan is required for Flatpak repository publishing"
    exit 1
  fi

  if ! command -v rsync >/dev/null 2>&1; then
    echo "::error::rsync is required on the Gitea runner for Flatpak repository publishing"
    exit 1
  fi

  mkdir -p "${HOME}/.ssh"
  chmod 700 "${HOME}/.ssh"

  SSH_KEY_FILE="${HOME}/.ssh/meshapp-flatpak-deploy"
  printf '%s\n' "${FLATPAK_DEPLOY_SSH_KEY}" | tr -d '\r' > "${SSH_KEY_FILE}"
  chmod 600 "${SSH_KEY_FILE}"

  ssh-keyscan -p "${DEPLOY_SSH_PORT}" -H "${FLATPAK_DEPLOY_HOST}" >> "${HOME}/.ssh/known_hosts"
}

check_remote_rsync() {
  local remote="$1"

  if ! ssh -i "${SSH_KEY_FILE}" -p "${DEPLOY_SSH_PORT}" -o StrictHostKeyChecking=yes "${remote}" \
      'command -v rsync >/dev/null 2>&1'; then
    echo "::error::rsync is required on ${FLATPAK_DEPLOY_HOST}. Install it on the Flatpak repository server."
    exit 1
  fi
}

publish_repo() {
  require_env FLATPAK_DEPLOY_HOST
  require_env FLATPAK_DEPLOY_USER
  require_env FLATPAK_DEPLOY_PATH

  import_gpg_key
  sign_repo_refs
  update_repo_summary
  write_flatpakref
  setup_ssh

  local remote deploy_path remote_repo remote_shell
  remote="${FLATPAK_DEPLOY_USER}@${FLATPAK_DEPLOY_HOST}"
  deploy_path="${FLATPAK_DEPLOY_PATH%/}"
  remote_repo="${deploy_path}/repo"
  remote_shell="ssh -i ${SSH_KEY_FILE} -p ${DEPLOY_SSH_PORT} -o StrictHostKeyChecking=yes"

  check_remote_rsync "${remote}"

  ssh -i "${SSH_KEY_FILE}" -p "${DEPLOY_SSH_PORT}" -o StrictHostKeyChecking=yes "${remote}" \
    "mkdir -p $(shell_quote "${remote_repo}")"

  rsync -az --exclude=/summary --exclude=/summary.sig \
    -e "${remote_shell}" "${REPO_DIR}/" "${remote}:${remote_repo}/"

  rsync -az \
    -e "${remote_shell}" "${REPO_DIR}/summary" "${REPO_DIR}/summary.sig" "${remote}:${remote_repo}/"

  rsync -az \
    -e "${remote_shell}" "${FLATPAKREF_PATH}" "${remote}:${deploy_path}/${APP_ID}.flatpakref"

  echo "Flatpak repository published to ${PUBLIC_BASE_URL%/}/repo/"
  echo "Flatpak ref published to ${PUBLIC_BASE_URL%/}/${APP_ID}.flatpakref"
}

command="${1:-publish}"

case "${command}" in
  import-key)
    import_gpg_key
    ;;
  sign)
    import_gpg_key
    sign_repo_refs
    ;;
  publish)
    publish_repo
    ;;
  *)
    echo "usage: $0 [import-key|sign|publish]"
    exit 2
    ;;
esac
