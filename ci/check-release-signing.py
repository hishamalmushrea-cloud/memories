#!/usr/bin/env python3
"""Decide whether a run may build a release artifact, and keep the wiring honest.

The rule this encodes, and why it exists:

**On a version tag, a release must be signed with the real key, or the run must fail.**
Before this check existed, a tag push with no secrets still succeeded - it built a
debug-signed APK and published a GitHub Release whose body said, in bold, that the
artifact was not release-signed. That is a warning in a place nobody reads on release
day, attached to an artifact that is installable and looks finished. A release that
cannot be signed is not a release, it is a mistake, and the only safe moment to say so is
before anything is built.

A manual run (`workflow_dispatch`, the rehearsal) may have no secrets: it is allowed to
build a debug-signed artifact and print what a real run would publish, because it cannot
publish anything itself. A *partially* configured setup - some of the four secrets and not
the others - is always a failure, on any trigger, because it is never intentional.

Two jobs, one file:

* **Static.** Release.yml must ask this script about a tag before building, must verify
  the certificate in the artifact afterwards, and must pass all four signing properties
  to Gradle. `build.gradle.kts` must keep reading the same four property names. A rename
  on either side would otherwise leave a release that builds green and unsigned.
* **Simulated.** `--ref` and `--signing` make the decision for a given situation, so the
  policy itself can be exercised on every push with fixture arguments (build.yml does
  that) instead of only being trusted because it looks right, and so the release workflow
  can use the *same* code path for real rather than a copy of the reasoning.

Usage:
    python3 ci/check-release-signing.py                        # static checks
    python3 ci/check-release-signing.py --ref REF --signing {complete,partial,none}
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE = ROOT / ".github" / "workflows" / "release.yml"
BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"

PROPERTIES = (
    "MEMORYMAP_KEYSTORE",
    "MEMORYMAP_KEYSTORE_PASSWORD",
    "MEMORYMAP_KEY_ALIAS",
    "MEMORYMAP_KEY_PASSWORD",
)

SECRETS = (
    "MEMORYMAP_KEYSTORE_B64",
    "MEMORYMAP_KEYSTORE_PASSWORD",
    "MEMORYMAP_KEY_ALIAS",
    "MEMORYMAP_KEY_PASSWORD",
)

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def static_checks() -> None:
    if not RELEASE.exists():
        fail(f"{RELEASE.relative_to(ROOT)} is missing")
        return
    text = RELEASE.read_text(encoding="utf-8")

    # 1 - the policy is consulted, for a tag, before anything is built.
    if "ci/check-release-signing.py --ref" not in text:
        fail(
            "release.yml never asks whether this run may build an unsigned artifact; a tag "
            "push with no secrets would then publish a debug-signed APK"
        )
    else:
        # The step must be allowed to fail the job, and must come before the build.
        guard = text.index("ci/check-release-signing.py --ref")
        build = text.index("-PMEMORYMAP_KEYSTORE" if "-PMEMORYMAP_KEYSTORE" in text else "gradlew assembleRelease")
        if guard > build:
            fail(
                "release.yml decides about signing after it has already built the artifact, "
                "which is too late to stop it"
            )
        step_start = text.rindex("\n      - ", 0, guard)
        step = text[step_start:guard]
        if "|| true" in step or "continue-on-error" in step:
            fail(
                "the signing decision in release.yml cannot fail the run, so a tag with no "
                "secrets would build a debug-signed artifact and carry on"
            )

    # 2 - the artifact is checked against the certificate this repository records.
    if "ci/verify-apk-signature.sh --release" not in text:
        fail(
            "release.yml does not verify the built artifact's certificate, so 'the build "
            "used the real key' is an assumption rather than a check"
        )
    else:
        step_start = text.rindex("\n      - ", 0, text.index("ci/verify-apk-signature.sh --release"))
        step = text[step_start:text.index("ci/verify-apk-signature.sh --release")]
        if "|| true" in step or "continue-on-error" in step:
            fail("the certificate verification in release.yml cannot fail the run")

    # 3 - every property the build reads is passed to it.
    for prop in PROPERTIES:
        if f"-P{prop}=" not in text:
            fail(f"release.yml never passes -P{prop}, so the signed build cannot happen")

    # 4 - and the build still reads those names.
    gradle = BUILD_GRADLE.read_text(encoding="utf-8")
    for prop in PROPERTIES:
        if f'findProperty("{prop}")' not in gradle:
            fail(f"app/build.gradle.kts no longer reads the {prop} property")

    # 5 - the secrets the workflow reads are the four the document names.
    for secret in SECRETS:
        if f"secrets.{secret}" not in text:
            fail(f"release.yml never reads the {secret} secret")


def verdict(ref: str, signing: str) -> tuple[bool, str]:
    """Whether this run may continue, and the sentence explaining it."""
    is_tag = bool(re.match(r"^refs/tags/v", ref or ""))

    if signing == "partial":
        return False, (
            "Some of the four signing secrets are set and some are not. A half-configured "
            "release is never intentional: either add all four (MEMORYMAP_KEYSTORE_B64, "
            "MEMORYMAP_KEYSTORE_PASSWORD, MEMORYMAP_KEY_ALIAS, MEMORYMAP_KEY_PASSWORD) or "
            "remove them all. See docs/RELEASE.md."
        )
    if is_tag and signing != "complete":
        return False, (
            f"This is a release: {ref} is a version tag and the signing secrets are not "
            "configured, so the build would produce a debug-signed APK. A release that "
            "cannot be signed is a mistake rather than a release, so nothing was built. "
            "Add the four secrets (docs/RELEASE.md) and push the tag again."
        )
    if is_tag:
        return True, "This is a signed release; the artifact will be checked against the recorded certificate."
    if signing == "complete":
        return True, "Not a tag, but the secrets are present: this run proves the real key signs the build."
    return True, (
        "A rehearsal: no tag and no secrets, so the artifact is signed with a throwaway key "
        "and this run cannot publish anything. Push a version tag to make a real release."
    )


def main() -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--ref", help="the ref being built, e.g. refs/tags/v0.1.0")
    parser.add_argument(
        "--signing",
        choices=("complete", "partial", "none"),
        help="whether the four signing secrets are configured",
    )
    arguments = parser.parse_args()

    if arguments.ref is None and arguments.signing is None:
        static_checks()
        for failure in failures:
            print(f"FAIL: {failure}")
        if failures:
            print(f"release signing check: FAIL ({len(failures)} problem(s))")
            return 1
        print("release signing check: PASS (a tag must be signed, and the artifact is verified)")
        return 0

    if arguments.ref is None or arguments.signing is None:
        print("--ref and --signing are used together", file=sys.stderr)
        return 2

    allowed, explanation = verdict(arguments.ref, arguments.signing)
    if allowed:
        print(f"release signing check: allowed - {explanation}")
        return 0
    print(f"release signing check: refused - {explanation}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
