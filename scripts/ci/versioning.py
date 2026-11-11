"""Release identity shared by build/publication tools; mirrored in Gradle and Java."""
import re
from dataclasses import dataclass

# Installer versions have their own epoch, above the previously shipped 2.x packages.
# MSI accepts three components bounded by 255, 255 and 65535.
MAX_BUILD_NUMBER = 253 * 256 * 65536 - 1
VERSION = re.compile(r"v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-unstable\.([1-9]\d*)|\+build\.([1-9]\d*))?")


def package_version(build):
    if not 1 <= build <= MAX_BUILD_NUMBER:
        raise ValueError(f"Build number must be in 1..{MAX_BUILD_NUMBER}")
    return f"{3 + build // (256 * 65536)}.{(build // 65536) % 256}.{build % 65536}"


@dataclass(frozen=True)
class ReleaseVersion:
    major: int
    minor: int
    patch: int
    channel: str
    build: int

    @property
    def base(self):
        return f"{self.major}.{self.minor}.{self.patch}"

    @property
    def tag(self):
        suffix = f"-unstable.{self.build}" if self.channel == "unstable" else f"+build.{self.build}" if self.build else ""
        return f"v{self.base}{suffix}"

    @property
    def code(self):
        # Retained for installed clients which only understand the old integer code.
        return self.major * 1_000_000 + self.minor * 1000 + self.patch - (self.channel == "unstable")

    @property
    def key(self):
        return self.major, self.minor, self.patch, self.channel == "stable", self.build

    @classmethod
    def parse(cls, value):
        match = VERSION.fullmatch(value)
        if not match:
            raise ValueError("Expected X.Y.Z, X.Y.Z-unstable.N or X.Y.Z+build.N (optional v prefix)")
        major, minor, patch = map(int, match.groups()[:3])
        build = int(match[4] or match[5] or 0)
        result = cls(major, minor, patch, "unstable" if match[4] else "stable", build)
        if major > 255 or minor > 255 or patch > 999 or result.code <= 0:
            raise ValueError("Version limits: major/minor <= 255, patch <= 999, positive version code")
        if build:
            package_version(build)
        return result


def with_build_number(value, build):
    # Manual runs may specify a bare -unstable suffix or a previous full tag.
    parsed = ReleaseVersion.parse(value + ".1" if value.endswith("-unstable") else value)
    package_version(build)
    return ReleaseVersion(parsed.major, parsed.minor, parsed.patch, parsed.channel, build)
