#!/usr/bin/env python3
"""Fail the build when a shipped artifact contains something that must never ship.

Everything else in `ci/` reads the source. This one reads the thing a person would
install: the APK and the Play bundle, after R8 has done whatever it is going to do. The
specification's hardest rule about the cloud is that the `service_role` key must never
be in the app, and a rule about a built artifact is only really checked on the artifact -
a source scan cannot see a key pasted into a resource, a `.so`, an asset, or a string
that a dependency brings along.

It is a small check on purpose: a few literal patterns, searched in every entry of the
zip, case-insensitively. What makes it worth having is the second half - the control. A
scan that finds nothing proves nothing unless it can be shown to find something, so each
artifact must also contain a string that is definitely there (`com.memorymap`, the
application id, which is in the manifest and in every generated references file). If the
control is missing, the scan is broken and the run fails, rather than reporting a clean
artifact it never actually read.

Run it after the artifacts are built:

    python3 ci/check-artifacts.py [path ...]

With no arguments it checks the debug APK, the release APK and the release bundle where
Gradle puts them. A path that does not exist is a failure: this check exists to look at
what was built, and silently skipping a missing file is how a check stops checking.
"""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

DEFAULT_ARTIFACTS = (
    ROOT / "app/build/outputs/apk/debug/app-debug.apk",
    ROOT / "app/build/outputs/apk/release/app-release.apk",
    ROOT / "app/build/outputs/bundle/release/app-release.aab",
)

# What must not be in a build a person installs, and why each one is here. These are
# literals, not patterns for guessing: a key that reaches an artifact reaches it as text.
FORBIDDEN = {
    "service_role": "the Supabase role that bypasses every row-level security policy",
    "service-role": "the same role, hyphenated, as it appears in URLs and dashboards",
    "supabase_service": "a service key's configuration name",
    "sk_live_": "a Stripe-style live secret key",
    "BEGIN PRIVATE KEY": "a PEM private key, which is what a signing or service key is",
}

# The control: proof that this scan can see into the artifact at all. The application id
# is in the manifest, in the resources table and in the generated BuildConfig references,
# so a build that does not contain it is not this app.
CONTROL = "com.memorymap"

TEXT_CHUNK = 8 * 1024 * 1024


def scan(path: pathlib.Path):
    """Every forbidden pattern found in the artifact, and whether the control was seen."""
    found = []
    control = False
    entries = 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            entries += 1
            with archive.open(info) as handle:
                # Read in chunks so a large asset does not have to fit in memory twice,
                # and overlap the chunks so a pattern spanning a boundary is still seen.
                previous = b""
                while True:
                    chunk = handle.read(TEXT_CHUNK)
                    if not chunk:
                        break
                    window = (previous + chunk).lower()
                    text = window.decode("latin-1", errors="replace")
                    if CONTROL in text:
                        control = True
                    for pattern, reason in FORBIDDEN.items():
                        if pattern.lower() in text:
                            found.append((info.filename, pattern, reason))
                    previous = chunk[-64:]
    return entries, control, found


def native_libraries(path: pathlib.Path) -> list[str]:
    """Every `lib/<abi>/*.so` in the artifact.

    Google Play requires the native libraries an app ships to be 64-bit where they exist
    and 16 KB page-size aligned, and asks for evidence rather than a promise. This project
    declares no NDK, no `jniLibs` and no `externalNativeBuild`, so the honest expectation is
    that the list is empty - and an expectation about a built artifact is worth checking on
    the artifact. A library appearing here means a dependency started shipping one, and the
    alignment question becomes real; it is a failure rather than a note so that the answer
    is never "we assumed".
    """
    with zipfile.ZipFile(path) as archive:
        return sorted(
            name for name in archive.namelist()
            if name.startswith("lib/") and name.endswith(".so")
        )


def main(argv: list[str]) -> int:
    paths = [pathlib.Path(argument) for argument in argv] or list(DEFAULT_ARTIFACTS)
    problems = []
    for path in paths:
        if not path.exists():
            problems.append(f"{path}: not built, so nothing was checked")
            continue
        try:
            entries, control, found = scan(path)
        except zipfile.BadZipFile as error:
            problems.append(f"{path}: not a readable archive ({error})")
            continue
        for entry, pattern, reason in found:
            problems.append(f"{path.name}: {entry} contains {pattern!r} - {reason}")
        if not control:
            problems.append(
                f"{path.name}: {CONTROL!r} is nowhere in {entries} entries, which means this "
                f"scan is not reading the artifact rather than that the artifact is clean"
            )
            continue
        libraries = native_libraries(path)
        if libraries:
            problems.append(
                f"{path.name}: ships {len(libraries)} native library(ies) ({libraries[:3]}"
                f"{' ...' if len(libraries) > 3 else ''}), so Google Play's 64-bit and 16 KB "
                f"page-size requirements apply and are not checked here - verify them with "
                f"`zipalign -c -P 16 -v 4` on the APK and on every ABI, then write the result "
                f"down rather than leaving this check to fail"
            )
            continue
        if not found:
            size = path.stat().st_size
            print(f"{path.name}: {entries} entries, {size:,} bytes, nothing forbidden, "
                  f"no native libraries")

    for problem in problems:
        print(problem)
    if problems:
        print(f"artifact check: FAIL ({len(problems)} problems)")
        return 1

    print(f"artifact check: PASS ({len(paths)} artifact(s) read, none carries a key it must "
          f"not, {len(FORBIDDEN)} patterns searched)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
