#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
private=.release-secrets/update-private-key.pem
public=config/update-public-key.pem
if [[ -e "$private" || -e "$public" ]]; then
  echo 'An update key already exists. Refusing to rotate the trust root.' >&2
  exit 1
fi
mkdir -p .release-secrets config
chmod 700 .release-secrets
openssl genpkey -algorithm Ed25519 -out "$private"
openssl pkey -in "$private" -pubout -out "$public"
chmod 644 "$public"
echo "Public key: $public"
echo "Private key saved locally: $private (gitignored; keep a separate backup)"
echo 'Upload it to the production environment secret MESHAPP_UPDATE_ED25519_PRIVATE_KEY.'
