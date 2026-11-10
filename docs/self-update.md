# MeshApp self-update

Build and publication instructions: [GitHub release workflow](github-release.md).

The native installer provides a stable launcher and Java runtime. The launcher
starts an application payload from a user-writable version directory. Updates
replace the payload and switch `current`; they do not modify the installed
launcher/runtime. Runtime or launcher changes require a new native installer.

Managed roots:

- macOS: `~/Library/Application Support/MeshApp/`
- Windows: `%LOCALAPPDATA%\MeshApp\`
- Linux: `~/.local/share/meshapp/`

Payload ZIPs contain `lib/`, including the application JAR, JavaFX modules and
native libraries, never a macOS `.app`. The packaged runtime must retain its
executable `bin/java`. `verifySelfUpdatePackage` checks packages before release.

## GitHub publication

`Build release` creates a draft with installers, payload ZIPs, `release.json`
and `SHA256SUMS`. `Publish release` validates these files, signs update entries,
uploads `meshapp.json`, publishes the release and deploys GitHub Pages.

Feed: `https://smikme.github.io/meshapp/updates/stable/meshapp.json`.
Artifact URLs use `https://github.com/smikme/meshapp/releases/download/<tag>/…`.

The new trust root is `config/update-public-key.pem`. The private key is stored
as `MESHAPP_UPDATE_ED25519_PRIVATE_KEY` in the `production` environment.
Do not rotate it without planning a trust transition for installed clients.
The first GitHub-only release requires manual installation.

Versions use `vX.Y.Z`; `versionCode = X * 1000000 + Y * 1000 + Z`.
Limits: X/Y <= 255 and Z <= 999. Development builds have code 0. The manifest and
every embedded `version.properties` must agree. A release cannot be promoted
over a newer stable release.

## Manifest contract

```json
{
  "version": "v2.4.0",
  "versionCode": 2004000,
  "releaseNotes": "Release notes",
  "releaseNotes_ru": "Описание выпуска",
  "downloads": {
    "macos-dmg-aarch64": "https://github.com/smikme/meshapp/releases/download/v2.4.0/MeshApp-v2.4.0-macos-aarch64.dmg",
    "macos": "https://github.com/smikme/meshapp/releases/tag/v2.4.0"
  },
  "selfUpdate": {
    "macos-aarch64": {
      "type": "full-archive",
      "format": "zip",
      "version": "v2.4.0",
      "url": "https://github.com/smikme/meshapp/releases/download/v2.4.0/MeshApp-v2.4.0-macos-aarch64-selfupdate.zip",
      "sha256": "<64 hexadecimal characters>",
      "size": 123456789,
      "signature": "<base64 Ed25519 signature>"
    }
  }
}
```

Installer lookup prefers `{os}-{package}-{arch}`, then `{os}-{arch}`, followed
by generic package/OS fallbacks. Generic URLs lead to the release page so unknown
CPUs are not silently assigned incompatible installers.

Self-update lookup uses `{os}-{package}-{arch}`, `{os}-{arch}`, `{os}-{package}`,
`{os}`. Flatpak ignores `selfUpdate`; users install the new bundle from Releases.

Each Ed25519 signature covers these UTF-8 bytes, including the final newline:

```text
meshapp-self-update-v1
{manifest.version}
{manifest.versionCode}
{artifact.type}
{artifact.format}
{artifact.version}
{artifact.url}
{artifact.sha256}
{artifact.size}
```

`scripts/ci/release.py` generates and verifies signatures before publication.
The Java client verifies the signature and archive SHA-256 before installation.
The JSON document is not itself signed; each update entry binds its contents
to the release version/code.

Development overrides: JVM property `meshapp.update.ed25519PublicKey`, environment
variable `MESHAPP_UPDATE_ED25519_PUBLIC_KEY`, or build property
`meshappUpdateEd25519PublicKey`. Unsigned development updates require explicit
`meshapp.update.allowUnsigned` / `MESHAPP_UPDATE_ALLOW_UNSIGNED`. GitHub release
workflows always require signatures.
