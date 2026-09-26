#!/usr/bin/env python3
"""Check the store listing stays inside the limits the stores enforce.

`fastlane/metadata/android/` is what both F-Droid and Google Play read, and both
reject or silently truncate a field that is over its limit. A truncated title is
worse than no title, and it is the kind of mistake that is only found at upload
time, when the upload is the thing being done.

The limits checked here:

    title.txt              30 characters
    short_description.txt  80 characters
    full_description.txt 4000 characters
    changelogs/<n>.txt    500 characters

`changelogs/<n>.txt` is named after the `versionCode` in `app/build.gradle.kts`,
so a version bump without a changelog file is a failure rather than a release
whose "what's new" is empty.

The Arabic listing is not a translation of the English one and is not checked
against it: the two are written for their own readers.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
METADATA = ROOT / "fastlane/metadata/android"
BUILD_FILE = ROOT / "app/build.gradle.kts"

LIMITS = {
    "title.txt": 30,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
CHANGELOG_LIMIT = 500

# The two listings this project writes. A third locale is welcome, but it has to
# be added here deliberately rather than appearing by accident.
LOCALES = ("ar", "en-US")


def version_code() -> int:
    """The `versionCode` the release will carry, so the changelog can be named."""
    text = BUILD_FILE.read_text(encoding="utf-8")
    match = re.search(r"versionCode\s*=\s*(\d+)", text)
    if match is None:
        raise SystemExit("could not read versionCode from app/build.gradle.kts")
    return int(match.group(1))


def main() -> int:
    if not METADATA.is_dir():
        raise SystemExit(f"missing {METADATA.relative_to(ROOT)}")

    code = version_code()
    problems = []

    found_locales = sorted(p.name for p in METADATA.iterdir() if p.is_dir())
    for locale in LOCALES:
        if locale not in found_locales:
            problems.append(f"missing locale directory {locale}/")
    for locale in found_locales:
        if locale not in LOCALES:
            problems.append(f"unexpected locale directory {locale}/ (add it to LOCALES)")

    for locale in found_locales:
        directory = METADATA / locale

        for name, limit in LIMITS.items():
            path = directory / name
            if not path.is_file():
                problems.append(f"{locale}/{name} is missing")
                continue
            text = path.read_text(encoding="utf-8")
            length = len(text.strip())
            if length == 0:
                problems.append(f"{locale}/{name} is empty")
            elif length > limit:
                problems.append(f"{locale}/{name} is {length} characters, limit {limit}")

        changelog = directory / "changelogs" / f"{code}.txt"
        if not changelog.is_file():
            problems.append(
                f"{locale}/changelogs/{code}.txt is missing; the versionCode is {code}"
            )
        else:
            text = changelog.read_text(encoding="utf-8")
            length = len(text.strip())
            if length == 0:
                problems.append(f"{locale}/changelogs/{code}.txt is empty")
            elif length > CHANGELOG_LIMIT:
                problems.append(
                    f"{locale}/changelogs/{code}.txt is {length} characters, "
                    f"limit {CHANGELOG_LIMIT}"
                )

    if problems:
        print("store metadata check: FAIL")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(
        "store metadata check: PASS "
        f"({len(found_locales)} locales, versionCode {code}, limits respected)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
