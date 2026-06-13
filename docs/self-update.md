# MeshApp Self-Update Manifest

MeshApp supports a non-privileged full-archive self-update path when it is
started from the managed layout produced by `./gradlew selfUpdateImage`.

The legacy `downloads` manifest still works. The self-update path is enabled
only when all of these are true:

- the launcher provides `MESHAPP_UPDATE_ROOT` and `MESHAPP_UPDATE_VERSION`
- the current package format is not Flatpak
- the manifest contains a matching `selfUpdate` artifact
- the artifact is a `full-archive` `zip`
- the artifact hash matches
- the artifact signature is trusted, or unsigned dev mode is explicitly enabled

The generated manifest keeps the legacy `downloads` map for existing clients:
`windows`, `macos`, `linux`, plus package-specific keys such as `windows-msi`,
`macos-dmg`, `linux-deb`, `linux-appimage`, and `linux-flatpak`. The new
`selfUpdate` map is additive; older clients ignore it.

Example:

```json
{
  "version": "2.1.20",
  "versionCode": 2147,
  "releaseNotes": "Bug fixes and improvements.",
  "releaseNotes_ru": "Исправления ошибок и улучшения.",
  "selfUpdate": {
    "macos-aarch64": {
      "type": "full-archive",
      "format": "zip",
      "version": "2.1.20",
      "url": "https://meshapp.privatepractice.app/releases/MeshApp-2.1.20-macos-aarch64-selfupdate.zip",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "size": 123456789,
      "signature": "base64-ed25519-signature"
    }
  }
}
```

Artifact lookup order is:

1. `{os}-{package}-{arch}`
2. `{os}-{arch}`
3. `{os}-{package}`
4. `{os}`

Flatpak builds intentionally ignore `selfUpdate` artifacts. They only notify the
user and instruct them to update through Software Center or:

```sh
flatpak update app.privatepractice.meshapp
```

## CI/CD generation

Release artifacts and the update manifest are separate CI/CD steps:

1. Tag pushes run `.gitea/workflows/release.yml`. Linux, Windows, and macOS
   jobs build native packages plus
   `MeshApp-{version}-{os}-{arch}-selfupdate.zip`.
   This creates and publishes the Gitea release, but does not publish it to the
   update system.
2. A manual `.gitea/workflows/publish-update-manifest.yml` run selects which
   existing Gitea release is visible to users by the tag chosen in the Gitea
   workflow ref menu. It downloads the selected release assets, computes SHA-256
   and file sizes, adds `downloads`, adds matching `selfUpdate` entries, and
   deploys `meshapp.json` to the Flatpak server at `${FLATPAK_DEPLOY_PATH}/meshapp.json`,
   which is publicly available as `https://flatpak.privatepractice.app/meshapp.json`.
   nginx for `meshapp.privatepractice.app` should reverse-proxy
   `/meshapp.json` to that Flatpak URL.

The manifest generator is `.gitea/scripts/generate-update-manifest.sh`.

```sh
.gitea/scripts/generate-update-manifest.sh v2.1.20 build/update/meshapp.json build/jpackage
```

CI variables and secrets:

- `RELEASE_TOKEN`: required; used to read selected Gitea release assets
- `MESHAPP_UPDATE_ED25519_PUBLIC_KEY`: optional at build time; bundled into
  `/update/ed25519-public-key.txt` so the client trusts signed artifacts
- `MESHAPP_UPDATE_ED25519_PRIVATE_KEY`: optional; signs self-update artifacts
- `MESHAPP_UPDATE_ASSET_BASE_URL`: optional; overrides generated asset URLs
- `MESHAPP_VERSION`: optional; overrides manifest `version`
- `MESHAPP_VERSION_CODE`: optional; overrides manifest `versionCode`
- `MESHAPP_RELEASE_NOTES`: optional; overrides manifest `releaseNotes`
- `MESHAPP_RELEASE_NOTES_RU`: optional; fills localized `releaseNotes_ru`
- `FLATPAK_DEPLOY_HOST`: required by `publish-update-manifest`
- `FLATPAK_DEPLOY_USER`: required by `publish-update-manifest`
- `FLATPAK_DEPLOY_PATH`: required by `publish-update-manifest`
- `FLATPAK_DEPLOY_SSH_KEY`: required by `publish-update-manifest`
- `FLATPAK_DEPLOY_SSH_PORT`: optional; defaults to `22`

Manual update publication inputs:

- selected workflow ref: required; choose an existing release tag, for example `v2.1.20`
- `release_notes`: required; exported as `MESHAPP_RELEASE_NOTES` for `meshapp.json`
- `release_notes_ru`: required; exported as `MESHAPP_RELEASE_NOTES_RU` for `meshapp.json`

The Gitea release body is generated from commit history during the tag release.
The manual release notes inputs are used only by the update manifest.

If `MESHAPP_UPDATE_ED25519_PRIVATE_KEY` is absent, the manifest is still
generated, but self-update signatures are omitted. If
`MESHAPP_UPDATE_ED25519_PUBLIC_KEY` is absent during the app build, production
clients will not trust signed self-update artifacts unless the public key is
provided later through a JVM property or environment variable.

The Ed25519 signature signs this UTF-8 payload:

```text
meshapp-self-update-v1
{manifest.version}
{manifest.versionCode}
{artifact.type or full-archive}
{artifact.format or zip}
{artifact.version}
{artifact.url}
{artifact.sha256}
{artifact.size}
```

The public key can be supplied through one of:

- JVM property `meshapp.update.ed25519PublicKey`
- environment variable `MESHAPP_UPDATE_ED25519_PUBLIC_KEY`
- resource `/update/ed25519-public-key.txt`

For local development only, unsigned artifacts can be enabled with
`meshapp.update.allowUnsigned=true` or `MESHAPP_UPDATE_ALLOW_UNSIGNED=true`.
