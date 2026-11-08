#!/usr/bin/env bash
# One-time repository setup. Requires an authenticated gh with repository admin access.
set -euo pipefail
cd "$(dirname "$0")/../.."
repo=smikme/meshapp
private=.release-secrets/update-private-key.pem
test -f "$private" || { echo "Missing $private; restore the private key matching config/update-public-key.pem" >&2; exit 1; }
command -v gh >/dev/null
gh auth status
derived="$(openssl pkey -in "$private" -pubout -outform DER | openssl dgst -sha256)"
expected="$(openssl pkey -pubin -in config/update-public-key.pem -outform DER | openssl dgst -sha256)"
test "$derived" = "$expected" || { echo 'Private/public key mismatch' >&2; exit 1; }
gh api --method PUT "repos/$repo/environments/production" --input - <<'JSON'
{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
if ! gh api "repos/$repo/environments/production/deployment-branch-policies" --jq '.branch_policies[].name' | grep -qx main; then
  gh api --method POST "repos/$repo/environments/production/deployment-branch-policies" -f name=main -f type=branch
fi
gh secret set MESHAPP_UPDATE_ED25519_PRIVATE_KEY --repo "$repo" --env production < "$private"
if gh api "repos/$repo/pages" >/dev/null 2>&1; then
  gh api --method PUT "repos/$repo/pages" -f build_type=workflow
else
  gh api --method POST "repos/$repo/pages" -f build_type=workflow
fi
echo 'Configured GitHub Pages and the production signing secret. Keep a backup of the private key.'
