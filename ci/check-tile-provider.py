#!/usr/bin/env python3
"""The tile-provider policy, as a decision rather than as a paragraph.

The app needs map tiles, and the tiles it uses come from somewhere with terms. The public
OpenStreetMap tile server is the default because it needs no account and works for a
development build, but its usage policy is explicit: a *distributed* app that asks
tile.openstreetmap.org for tiles needs the foundation's prior permission, and a heavy
user is expected to move to their own hosting or a provider that allows the volume
(docs/SERVICE_LIMITS.md section 2). That is a publishing question, not a coding one, and
"we will remember to change it before releasing" is not a mechanism.

So the same shape as the signing policy is used here, for the same reason:

  * a `v*` tag whose effective tile server is still the public OSM host is refused
    **before anything is built**, unless the owner states, in a repository variable, that
    permission was granted;
  * a tag whose template needs a key (it contains `{key}`) but has none is refused: the
    app would draw blank squares and nothing would say why;
  * a tag whose template is malformed is refused, because the app falls back to the OSM
    host at runtime and the fallback is silent on the screen;
  * anything else - every ordinary push, every manual run - is allowed, and the check
    prints which provider the build would use.

Run it with no arguments for the static half (the files still agree with each other), or
with --ref/--tile-server/--key/--permitted to ask the policy a question. Exit 0 means the
run may proceed; anything else prints one sentence saying what to change.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

OSM_HOST = "tile.openstreetmap.org"
BUILD = ROOT / "app/build.gradle.kts"
RELEASE = ROOT / ".github/workflows/release.yml"
SERVICE_LIMITS = ROOT / "docs/SERVICE_LIMITS.md"
PROVIDER = ROOT / "app/src/main/java/com/memorymap/data/map/TileServerMapProvider.kt"

# Where the four ways a misconfiguration is refused, so the static half can insist that
# the pieces that make the refusal possible still exist.
STATIC_PROBLEMS: list[str] = []


def static_checks() -> list[str]:
    """What must stay true in the source for this policy to mean anything."""
    problems = []

    build = BUILD.read_text(encoding="utf-8")
    if 'secret("MAP_TILE_SERVER")' not in build:
        problems.append(
            "app/build.gradle.kts no longer reads MAP_TILE_SERVER, so the tile host can "
            "only be changed by editing the build file"
        )
    if 'buildConfigField("String", "MAP_TILE_KEY"' not in build:
        problems.append(
            "app/build.gradle.kts no longer exposes MAP_TILE_KEY, so a provider that "
            "needs a key cannot be configured at all"
        )
    if "MAP_MAX_ZOOM" not in build:
        problems.append("app/build.gradle.kts no longer exposes MAP_MAX_ZOOM")

    provider = PROVIDER.read_text(encoding="utf-8")
    if 'replace("{key}"' not in provider:
        problems.append(
            "TileServerMapProvider no longer substitutes {key}, so a keyed provider's "
            "template would be requested literally"
        )
    if "OSM_HOST" not in provider:
        problems.append("TileServerMapProvider no longer names the policy-limited OSM host")

    release = RELEASE.read_text(encoding="utf-8")
    if "check-tile-provider.py" not in release:
        problems.append(
            "release.yml does not consult this policy, so a tag could publish with the "
            "public OSM tiles"
        )
    else:
        # The check has to be consulted before the build, and before anything publishes.
        consult = release.index("check-tile-provider.py")
        build_step = release.find("./gradlew assembleRelease")
        publish = release.find("gh release create")
        if build_step != -1 and consult > build_step:
            problems.append(
                "release.yml consults the tile policy only after the build, so a refused "
                "configuration still costs a full build"
            )
        if publish != -1 and consult > publish:
            problems.append(
                "release.yml consults the tile policy after publishing, which is too late"
            )

    limits = SERVICE_LIMITS.read_text(encoding="utf-8")
    for name in ("MAP_TILE_SERVER", "MAP_TILE_KEY"):
        if name not in limits:
            problems.append(
                f"docs/SERVICE_LIMITS.md never mentions {name}, so the person who has to "
                f"configure a provider has nowhere to read how"
            )

    return problems


def default_from_build() -> str:
    """The template app/build.gradle.kts uses when MAP_TILE_SERVER is not set.

    Read from the build file rather than copied here: the workflow resolves the same
    value the build will, so a default that changes in one place cannot be missed in the
    other. The pattern is the `.ifBlank { "..." }` that follows the lookup.
    """
    build = BUILD.read_text(encoding="utf-8")
    match = re.search(
        r'secret\("MAP_TILE_SERVER"\)\s*\.ifBlank\s*\{\s*"([^"]+)"', build
    )
    return match.group(1) if match else ""


def verdict(ref: str, tile_server: str, key_present: bool, permitted: bool) -> tuple[bool, str]:
    """May this run build? Returns (allowed, one sentence)."""
    is_tag = ref.startswith("refs/tags/v")
    usable = all(token in tile_server for token in ("{z}", "{x}", "{y}"))
    needs_key = "{key}" in tile_server
    on_public_osm = OSM_HOST in tile_server

    if not usable:
        if is_tag:
            return False, (
                "the configured tile template has no {z}/{x}/{y}, so the app would fall "
                "back to tile.openstreetmap.org at runtime and this repository would never "
                "say so. Fix MAP_TILE_SERVER before tagging."
            )
        return True, "the tile template is malformed, but this run cannot publish"

    if needs_key and not key_present:
        if is_tag:
            return False, (
                "the tile template asks for a key ({key}) and none is set, so the map "
                "would show blank squares. Set MEMORYMAP_TILE_KEY before tagging."
            )
        return True, "the tile template wants a key and none is set; this run cannot publish"

    if on_public_osm:
        if not is_tag:
            return True, (
                "the public OpenStreetMap tiles will be used; that is allowed for a "
                "development build and refused for a release"
            )
        if permitted:
            return True, (
                "the public OpenStreetMap tiles will be used, and this repository states "
                "that permission for a distributed app was granted"
            )
        return False, (
            "a release would ask tile.openstreetmap.org for tiles, and their policy "
            "requires the foundation's prior permission for a distributed app. Set "
            "MEMORYMAP_TILE_SERVER to a provider that allows the volume (docs/SERVICE_LIMITS.md "
            "section 2), or set the repository variable MEMORYMAP_OSM_PERMITTED to record "
            "that permission was granted."
        )

    provider = tile_server.split("/")[2] if tile_server.count("/") >= 3 else tile_server
    if is_tag:
        return True, f"the release would use the configured tile provider: {provider}"
    return True, f"the build would use the configured tile provider: {provider}"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--ref", help="the ref the run is for, e.g. refs/tags/v0.1.0")
    parser.add_argument("--tile-server", help="the effective MAP_TILE_SERVER value")
    parser.add_argument("--key", action="store_true", help="a tile key is set")
    parser.add_argument(
        "--permitted",
        action="store_true",
        help="the repository records that OSM permission was granted",
    )
    parser.add_argument(
        "--from-build",
        action="store_true",
        help="resolve the ref, the tile server, the key and the permission from the "
        "environment the way the workflow does",
    )
    args = parser.parse_args(argv)

    if args.from_build:
        args.ref = args.ref or os.environ.get("GITHUB_REF", "")
        args.tile_server = args.tile_server or os.environ.get("MAP_TILE_SERVER", "") \
            or default_from_build()
        args.key = args.key or bool(os.environ.get("MAP_TILE_KEY", "").strip())
        args.permitted = args.permitted or bool(
            os.environ.get("MEMORYMAP_OSM_PERMITTED", "").strip()
        )
        if not args.tile_server:
            print(
                "FAIL: neither MAP_TILE_SERVER nor a default in app/build.gradle.kts could "
                "be read, so the tile source of this build is unknown"
            )
            print("tile provider check: FAIL (1 problem)")
            return 1

    problems = static_checks()
    for problem in problems:
        print(f"FAIL: {problem}")

    if args.ref:
        allowed, sentence = verdict(args.ref, args.tile_server or "", args.key, args.permitted)
        print(sentence)
        if not allowed:
            problems.append(sentence)

    if problems:
        print(f"tile provider check: FAIL ({len(problems)} problem(s))")
        return 1

    if args.ref:
        print("tile provider check: PASS (the policy answered, and the source agrees)")
    else:
        print(
            "tile provider check: PASS (the tile host, its credit and its key are build "
            "settings, and release.yml asks this policy before it builds)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
