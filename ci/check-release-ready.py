#!/usr/bin/env python3
"""One command that answers the only question that matters before a tag: may I release?

The release path is guarded, but the guards are spread across five scripts and two
workflows, and nothing answered the owner's actual question - "is this repository ready
for me to push `v0.1.0`?" - in one place. So the conditions were checked one at a time,
or not until the tag was pushed and a build failed four minutes in.

This is the aggregator. It runs the static release guards as subprocesses (it does not
re-implement them; each keeps its own verdict and its own self-test), adds the one
condition nothing else validates ahead of time - that `ci/release-fingerprint.txt`
records a well-formed certificate fingerprint, which `verify-apk-signature.sh` reads on
the release run and would otherwise fail *after* a signed build - and prints the list of
GitHub secrets and variables the owner has to create, cross-referenced with
`docs/RELEASE.md` so the list cannot drift from the documentation.

The verdict is the AND of every blocking condition. A non-zero exit means "do not tag
yet", and the output names what is missing and which file or setting fixes it.

What this cannot check, and says so rather than guessing: the *values* of the signing
secrets and the tile key live in GitHub, not in the repository, so their presence is
knowable only inside a workflow run. `release.yml` asks `check-release-signing.py` and
`check-tile-provider.py` about those at the moment it has them; this lists what the owner
must create and confirms the documentation names every one of them.

Run it from anywhere in the repository:

    python3 ci/check-release-ready.py            # the readiness verdict
    python3 ci/check-release-ready.py --self-test  # prove the checker against fixtures
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The static guards a release depends on. Run as subprocesses so each keeps its own
# logic, verdict and self-test; this file aggregates, it does not duplicate. `GITHUB_TOKEN`
# is provided by Actions itself and is never something the owner creates.
GUARDS = (
    "ci/check-release-notes.py",
    "ci/check-store-metadata.py",
    "ci/check-tile-provider.py",
    "ci/check-release-signing.py",
)

# A certificate fingerprint as `ci/release-fingerprint.txt` records it: `SHA256=` then 64
# upper-case hex digits, no colons. `verify-apk-signature.sh` and the release step both
# read this line; a malformed one fails on the release run, after a signed build, which is
# the most expensive moment to discover a typo.
FINGERPRINT = re.compile(r"^SHA256=([0-9A-F]{64})$")

# Secrets and variables a workflow reads. Digits are part of the name (`..._B64`), which a
# letters-and-underscores pattern silently truncates - the kind of near-match that makes a
# cross-reference look complete when it is not.
WORKFLOW_CREDENTIAL = re.compile(r"\b(?:secrets|vars)\.([A-Z][A-Z0-9_]*)")
AUTOMATIC = {"GITHUB_TOKEN"}


def version(root: pathlib.Path) -> tuple[str, int] | None:
    """The `versionName` and `versionCode` the release will carry, or None."""
    gradle = root / "app/build.gradle.kts"
    if not gradle.is_file():
        return None
    text = gradle.read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        return None
    return name.group(1), int(code.group(1))


def fingerprint(root: pathlib.Path) -> tuple[bool, str]:
    """Whether `ci/release-fingerprint.txt` records a well-formed certificate fingerprint.

    This is the one release condition no other guard validates ahead of time. The file is
    read on the release run by `verify-apk-signature.sh`; until now a missing or malformed
    line surfaced only there, after Gradle had built and signed both artifacts.
    """
    path = root / "ci/release-fingerprint.txt"
    if not path.is_file():
        return False, "ci/release-fingerprint.txt is missing"
    for line in path.read_text(encoding="utf-8").splitlines():
        if FINGERPRINT.match(line.strip()):
            return True, "the release certificate fingerprint is recorded and well-formed"
    return False, (
        "ci/release-fingerprint.txt has no `SHA256=<64 hex>` line; the release run reads "
        "this to verify the signed artifact and would fail after building it"
    )


def credentials(root: pathlib.Path) -> tuple[list[str], list[str]]:
    """The secrets/variables the release workflow reads, and which the docs do not name.

    Returns (required, undocumented). `required` is what the owner must create in GitHub;
    `undocumented` is the subset `docs/RELEASE.md` does not mention, which is a blocking
    gap: a credential a workflow reads but no document names is one the owner cannot know
    to create. `check-docs.py` enforces this cross-reference on every push; it is repeated
    here because the release moment is where an owner acts on the list, and a list that
    could be wrong is worse than no list.
    """
    release = root / ".github/workflows/release.yml"
    docs = root / "docs/RELEASE.md"
    if not release.is_file():
        return [], []
    required = sorted(
        {m for m in WORKFLOW_CREDENTIAL.findall(release.read_text(encoding="utf-8"))}
        - AUTOMATIC
    )
    documented = docs.read_text(encoding="utf-8") if docs.is_file() else ""
    undocumented = [name for name in required if name not in documented]
    return required, undocumented


def evaluate(root: pathlib.Path) -> tuple[bool, list[tuple[str, bool, str]]]:
    """Every blocking condition as (label, ok, detail), and whether all of them hold."""
    checks: list[tuple[str, bool, str]] = []

    # 1 - the version the tag would carry has to be readable, because every other
    #     condition (the changelog section, the fastlane changelog) is keyed to it.
    parsed = version(root)
    if parsed is None:
        checks.append(("version", False,
                       "could not read versionName and versionCode from app/build.gradle.kts"))
    else:
        name, code = parsed
        checks.append(("version", True, f"the release would be {name} (versionCode {code})"))

    # 2 - the certificate fingerprint, the condition nothing else checks ahead of time.
    ok, detail = fingerprint(root)
    checks.append(("fingerprint", ok, detail))

    # 3 - the static guards, each run as its own process so its own verdict stands.
    for guard in GUARDS:
        path = root / guard
        if not path.is_file():
            checks.append((guard, False, f"{guard} is missing"))
            continue
        result = subprocess.run([sys.executable, str(path)], capture_output=True, text=True)
        last = next((line for line in reversed(result.stdout.splitlines()) if line.strip()), "")
        checks.append((guard, result.returncode == 0, last or f"exited {result.returncode}"))

    # 4 - every credential the release workflow reads is documented, so the owner's
    #     checklist cannot miss one.
    required, undocumented = credentials(root)
    if undocumented:
        checks.append(("credentials documented", False,
                       "docs/RELEASE.md does not name: " + ", ".join(undocumented)))
    else:
        checks.append(("credentials documented", True,
                       f"all {len(required)} secrets/variables the release reads are in docs/RELEASE.md"))

    return all(ok for _, ok, _ in checks), checks


def main() -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--self-test", action="store_true",
                        help="prove the checker against fixtures and stop")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    ready, checks = evaluate(ROOT)
    print("Release readiness")
    for label, ok, detail in checks:
        print(f"  [{'x' if ok else ' '}] {label}: {detail}")

    required, _ = credentials(ROOT)
    parsed = version(ROOT)
    print()
    if required:
        print("Create these in GitHub → Settings → Secrets and variables → Actions")
        print("(their values live in GitHub, so this cannot confirm they are set - only")
        print(" release.yml can, at the moment it reads them):")
        release_text = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        for name in required:
            kind = "variable" if f"vars.{name}" in release_text else "secret"
            print(f"  - {name}  ({kind})")
    if parsed:
        print(f"\nTo release: git tag v{parsed[0]} && git push origin v{parsed[0]}")

    print()
    if ready:
        print("release-ready: PASS (every static condition holds; a tag may be pushed)")
        return 0
    print("release-ready: FAIL (do not tag yet; the unchecked boxes above are what is missing)")
    return 1


def self_test() -> int:
    """Prove the checker against repositories built in memory, needing no real release.

    The conditions this file owns - the version reader, the fingerprint format, the
    credential cross-reference - are the ones a typo would slip past, so each is given a
    fixture that passes and one that fails. The aggregated guards are not re-run here;
    they carry their own self-tests and their own runs in `build.yml`.
    """
    failures: list[str] = []

    def check(condition: bool, description: str) -> None:
        if not condition:
            failures.append(description)

    def fixture(directory: pathlib.Path, *, fingerprint_line: str | None,
                release_credentials: str, docs_names: list[str]) -> pathlib.Path:
        (directory / "app").mkdir(parents=True, exist_ok=True)
        (directory / "app/build.gradle.kts").write_text(
            'versionCode = 7\nversionName = "1.2.3"\n', encoding="utf-8")
        (directory / "ci").mkdir(parents=True, exist_ok=True)
        if fingerprint_line is not None:
            (directory / "ci/release-fingerprint.txt").write_text(
                f"# comment\n{fingerprint_line}\n", encoding="utf-8")
        (directory / ".github/workflows").mkdir(parents=True, exist_ok=True)
        (directory / ".github/workflows/release.yml").write_text(
            release_credentials, encoding="utf-8")
        (directory / "docs").mkdir(parents=True, exist_ok=True)
        (directory / "docs/RELEASE.md").write_text(
            "\n".join(docs_names), encoding="utf-8")
        return directory

    good_fp = "SHA256=" + "A1" * 32

    with tempfile.TemporaryDirectory() as temporary:
        root = pathlib.Path(temporary)

        # A complete repository passes every condition this file owns. The credential set
        # deliberately includes one whose name ends in a digit (`..._B64`): a pattern that
        # matched only letters and underscores would read it as `MEMORYMAP_KEYSTORE_B`,
        # and the cross-reference would look complete while naming a secret that does not
        # exist. The fixture is what makes that truncation a failure rather than a comment.
        complete = fixture(root / "complete", fingerprint_line=good_fp,
                           release_credentials="secrets.MEMORYMAP_KEY_ALIAS\nsecrets.MEMORYMAP_KEYSTORE_B64\nvars.MEMORYMAP_TILE_SERVER\nsecrets.GITHUB_TOKEN\n",
                           docs_names=["MEMORYMAP_KEY_ALIAS", "MEMORYMAP_KEYSTORE_B64", "MEMORYMAP_TILE_SERVER"])
        parsed = version(complete)
        check(parsed == ("1.2.3", 7), f"version reader returned {parsed}, wanted ('1.2.3', 7)")
        ok, _ = fingerprint(complete)
        check(ok, "a well-formed fingerprint was rejected")
        required, undocumented = credentials(complete)
        # `sorted` compares whole strings, so `KEYSTORE` (S=0x53) precedes `KEY_ALIAS`
        # (_=0x5F); the expectation is written in the order Python actually produces
        # rather than the order that looks alphabetical to a reader.
        check(required == ["MEMORYMAP_KEYSTORE_B64", "MEMORYMAP_KEY_ALIAS", "MEMORYMAP_TILE_SERVER"],
              f"GITHUB_TOKEN must be excluded, the digit-ending name read whole, and the rest "
              f"sorted; got {required}")
        check(undocumented == [], f"a documented credential was called undocumented: {undocumented}")

        # A missing fingerprint file fails, and says the file is missing.
        missing = fixture(root / "missing-fp", fingerprint_line=None,
                          release_credentials="", docs_names=[])
        ok, detail = fingerprint(missing)
        check(not ok and "missing" in detail, "a missing fingerprint file was not reported as missing")

        # A malformed fingerprint (colons, short, lower-case) fails rather than passing a
        # value the release run would then choke on.
        for bad in ("SHA256=A1:A2", "SHA256=" + "a1" * 32, "SHA256=" + "A1" * 31, "F76390B1"):
            malformed = fixture(root / f"bad-{abs(hash(bad))}", fingerprint_line=bad,
                                release_credentials="", docs_names=[])
            ok, _ = fingerprint(malformed)
            check(not ok, f"a malformed fingerprint was accepted: {bad!r}")

        # A credential the workflow reads but the docs do not name is a blocking gap.
        gap = fixture(root / "gap", fingerprint_line=good_fp,
                      release_credentials="secrets.MEMORYMAP_KEY_ALIAS\nsecrets.MEMORYMAP_TILE_KEY\n",
                      docs_names=["MEMORYMAP_KEY_ALIAS"])
        _, undocumented = credentials(gap)
        check(undocumented == ["MEMORYMAP_TILE_KEY"],
              f"an undocumented credential was not caught: {undocumented}")

        # The version reader returns None rather than guessing when the build file is absent.
        empty = root / "empty"
        empty.mkdir()
        check(version(empty) is None, "a missing build.gradle.kts did not yield None")

    if failures:
        print("release-ready self-test: FAIL")
        for message in failures:
            print(f"  - {message}")
        return 1
    print("release-ready self-test: PASS (the version reader, the fingerprint format and "
          "the credential cross-reference are each proven against a fixture that passes "
          "and one that fails)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
