#!/usr/bin/env python3
"""Runs the release-notes extraction, so a tag is never the first time it runs.

`ci/release-notes.sh` builds the notes of a GitHub release from this project's
changelog. It is used only by the release workflow, which runs only on a version
tag - so without this check, every line of it is executed for the first time at the
one moment there is nothing to compare against: a published release with empty or
wrong notes.

What it checks, by running the script rather than by reading it:

1. The version in `app/build.gradle.kts` has a section in `CHANGELOG.md`. A tag for
   a version with no section publishes the whole changelog instead of the notes.
2. That section comes out whole: it starts with the version's own heading and does
   not contain the `[Unreleased]` heading, which is the next section down.
3. A tag with no section fails, rather than succeeding with nothing in it. The
   release workflow branches on that exit code to warn and fall back.
4. The version is matched as a whole heading and not as a substring anywhere on the
   line. The first version of the extraction was an `awk` whose test was "does this
   line contain the version", so tagging `v1.0.0` against a changelog that also has
   a `## [10.1.0]` section could publish the wrong section. The case is constructed
   here rather than waited for.
5. The release workflow calls the script, so there is one implementation of what a
   release's notes are.

    python3 ci/check-release-notes.py
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "ci" / "release-notes.sh"
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE = ROOT / "app" / "build.gradle.kts"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def run(tag: str, changelog: pathlib.Path | None = None) -> subprocess.CompletedProcess[str]:
    command = ["bash", str(SCRIPT), tag]
    if changelog is not None:
        command.append(str(changelog))
    return subprocess.run(command, capture_output=True, text=True, cwd=ROOT)


def main() -> int:
    if not SCRIPT.exists():
        fail("ci/release-notes.sh is missing")
        return report()
    if not CHANGELOG.exists():
        fail("CHANGELOG.md is missing")
        return report()

    gradle = GRADLE.read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not match:
        fail("app/build.gradle.kts has no versionName to take a release from")
        return report()
    version = match.group(1)

    # 1 - the version being built has notes.
    headings = re.findall(r"^## \[([^\]]+)\]", CHANGELOG.read_text(encoding="utf-8"), re.MULTILINE)
    if version not in headings:
        fail(
            f"CHANGELOG.md has no '## [{version}]' section, which is the version in "
            f"app/build.gradle.kts; a tag for it would publish the whole changelog. "
            f"Sections present: {', '.join(headings[:6])}"
        )
    if not headings:
        fail("CHANGELOG.md has no '## [version]' headings at all")

    # 2 - that section comes out whole, and only that section.
    result = run(f"v{version}")
    if result.returncode != 0:
        fail(f"the extraction failed for v{version}: {result.stderr.strip()}")
    else:
        notes = result.stdout
        if not notes.strip():
            fail(f"the extraction produced nothing for v{version}")
        else:
            first = notes.splitlines()[0]
            if not first.startswith(f"## [{version}]"):
                fail(f"the notes for v{version} start with {first!r}")
            if "[Unreleased]" in notes:
                fail("the notes for the current version contain the Unreleased section")
            if re.search(r"^\[[^\]]+\]:", notes, re.MULTILINE):
                fail("the notes end with the changelog's link definitions")

    # 3 - a tag with no section is a failure, not an empty file.
    missing = run("v9.9.9")
    if missing.returncode == 0:
        fail("a tag with no changelog section exits 0; the caller cannot tell")
    if missing.stdout.strip():
        fail("a tag with no changelog section still printed something")

    # 4 - a version is a whole heading, not a substring.
    #
    # The shadow version is this project's real one with a digit in front, which is
    # always a string that *contains* it: 0.1.0 sits inside 10.1.0, 1.0.0 inside
    # 11.0.0. So this case stays a real test of substring matching whatever the
    # version becomes, and it is the direction that actually bites - asking for the
    # current version must never return a future version's notes.
    shadow = f"1{version}"
    with tempfile.TemporaryDirectory() as directory:
        fake = pathlib.Path(directory) / "CHANGELOG.md"
        fake.write_text(
            "# Changelog\n\n"
            "## [Unreleased]\n\nlater\n\n"
            f"## [{shadow}] - 2030-01-01\n\nthe wrong section\n\n"
            f"## [{version}] - 2029-01-01\n\nthe right section\n\n"
            "[Unreleased]: https://example.invalid/commits\n"
            f"[{version}]: https://example.invalid/tag\n",
            encoding="utf-8",
        )
        substring = run(f"v{version}", fake)
        if substring.returncode != 0:
            fail(f"the extraction failed on the constructed changelog: {substring.stderr.strip()}")
        else:
            if "the wrong section" in substring.stdout:
                fail(
                    f"a tag of v{version} matched the '## [{shadow}]' section as well: the "
                    "version is being matched as a substring of a heading"
                )
            if "the right section" not in substring.stdout:
                fail(f"a tag of v{version} did not produce its own section")
            if "[Unreleased]" in substring.stdout:
                fail("the constructed section ran past into Unreleased")

    # 5 - one implementation, used by the workflow.
    workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8") if RELEASE_WORKFLOW.exists() else ""
    if "ci/release-notes.sh" not in workflow:
        fail("release.yml does not call ci/release-notes.sh; its notes would be built by a second implementation")

    return report()


def report() -> int:
    if failures:
        print("release notes check: FAIL")
        for message in failures:
            print(f"  - {message}")
        return 1
    print(
        "release notes check: PASS (the version being built has a section, the "
        "extraction returns exactly that section, a missing one fails instead of "
        "printing nothing, and a version is matched whole rather than as a substring)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
