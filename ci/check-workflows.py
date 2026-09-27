#!/usr/bin/env python3
"""Checks the workflows against each other and against what they read.

The two workflow files describe the same project, and nothing compared them. That
already cost this repository twice.

`release.yml` carried its own copy of the Android SDK step, written before anything
had run it, and it had three ways to fail on a first release: `yes | sdkmanager`
taking SIGPIPE under `set -euo pipefail`, a `build-tools` revision treated as
required where the build treats it as best effort, and an SDK path that was an
unbound variable when neither `ANDROID_HOME` nor `ANDROID_SDK_ROOT` was set. The
build workflow had the same step working, and nothing was comparing the two. (When
this check was written it immediately found a third difference: the release used
`actions/setup-java@v4` where the build used `@v5`.)

The build workflow also had a step - "Check the Gradle configuration" - whose
output no log captured, so three runs in a row failed at configuration time
without a visible cause. The report reads `/tmp/*.log`; a stage nobody writes to
nobody can explain.

So this checks, without needing a YAML library:

1. Every `uses:` is pinned to a version, and an action used in both files is used
   at the same version in both.
2. The Android SDK install step is character-for-character the same step in both.
3. Every `/tmp/*.log` the build workflow reads is written by one of its steps, and
   every Gradle step it runs writes a log that something reads.
4. Gradle steps in the build workflow do not decide the job (it reports through the
   gate at the end, which reads the logs), while the release workflow never swallows
   a failure of the release build.
5. `release.yml` triggers on a version tag and by hand, and only a version tag
   publishes.

Run: python3 ci/check-workflows.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / ".github" / "workflows" / "build.yml"
RELEASE = ROOT / ".github" / "workflows" / "release.yml"

SDK_STEP = "Install the Android SDK packages the project needs"

USES_RE = re.compile(r"^\s*-?\s*uses:\s*([\w.\-/]+)@(\S+)\s*$")
USES_UNPINNED_RE = re.compile(r"^\s*uses:\s*(?!\./)([\w.\-/]+)\s*$")
STEP_RE = re.compile(r"^ {6}- ")
NAME_RE = re.compile(r"^\s*-?\s*name:\s*(.+?)\s*$")
# `run: |` is a block; `run: something` is one line. Both are steps that run.
RUN_BLOCK_RE = re.compile(r"^(\s*)run:\s*\|-?\s*$")
RUN_INLINE_RE = re.compile(r"^\s*run:\s*(?![|>])(.+?)\s*$")
LOG_WRITE_RE = re.compile(r"tee\s+/tmp/([a-z_]+\.log)|>\s*/tmp/([a-z_]+\.log)")
LOG_ANY_RE = re.compile(r"/tmp/([a-z_]+\.log)")

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


class Step:
    """One entry under `steps:`, as the file writes it."""

    def __init__(self, lines: list[str], path: str, index: int) -> None:
        self.lines = lines
        self.where = f"{path} step {index}"
        self.name = ""
        for line in lines:
            match = NAME_RE.match(line)
            if match and not self.name:
                self.name = match.group(1).strip("\"'")
        self.uses = ""
        for line in lines:
            match = USES_RE.match(line)
            if match:
                self.uses = f"{match.group(1)}@{match.group(2)}"
        self.run = self._run_body()

    def _run_body(self) -> str:
        for position, line in enumerate(self.lines):
            block = RUN_BLOCK_RE.match(line)
            if block:
                indent = len(block.group(1))
                body: list[str] = []
                for following in self.lines[position + 1 :]:
                    if not following.strip():
                        body.append("")
                        continue
                    if len(following) - len(following.lstrip()) <= indent:
                        break
                    body.append(following)
                # De-indent to the shallowest line so two copies compare as text.
                widths = [len(l) - len(l.lstrip()) for l in body if l.strip()]
                cut = min(widths) if widths else 0
                return "\n".join(l[cut:] if l.strip() else "" for l in body).strip()
            inline = RUN_INLINE_RE.match(line)
            if inline:
                return inline.group(1).strip()
        return ""

    def label(self) -> str:
        return self.name or self.uses or self.where


def steps_of(path: Path) -> list[Step]:
    if not path.exists():
        fail(f"{path.relative_to(ROOT)} is missing")
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    starts = [i for i, line in enumerate(lines) if STEP_RE.match(line)]
    if not starts:
        fail(f"{path.relative_to(ROOT)} has no steps this check can read")
        return []
    blocks = []
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(lines)
        blocks.append(Step(lines[start:end], str(path.relative_to(ROOT)), position + 1))
    return blocks


def find(steps: list[Step], name: str) -> Step | None:
    for step in steps:
        if step.name == name:
            return step
    return None


def main() -> int:
    build_steps = steps_of(BUILD)
    release_steps = steps_of(RELEASE)
    if not build_steps or not release_steps:
        return report()

    # 0 - nothing unpinned, nothing following a branch that moves.
    moving = {"main", "master", "head", "develop", "latest"}
    for path in (BUILD, RELEASE):
        for line in path.read_text(encoding="utf-8").splitlines():
            match = USES_UNPINNED_RE.match(line)
            if match:
                fail(
                    f"{path.name} uses {match.group(1)} without a version; a workflow "
                    "should not follow a moving branch"
                )
                continue
            pinned = USES_RE.match(line)
            if pinned and pinned.group(2).lower() in moving:
                fail(
                    f"{path.name} uses {pinned.group(1)}@{pinned.group(2)}: a ref that "
                    "moves means the workflow can change without a commit here"
                )

    # 1 - an action used twice is the same version twice.
    def versions(steps: list[Step]) -> dict[str, str]:
        found: dict[str, str] = {}
        for step in steps:
            if step.uses:
                name, _, version = step.uses.partition("@")
                found[name] = version
        return found

    build_versions, release_versions = versions(build_steps), versions(release_steps)
    if len(build_versions) < 3 or len(release_versions) < 3:
        fail(
            "fewer than three actions were read from one of the workflows, which "
            f"means this check is no longer reading them (build: {sorted(build_versions)}, "
            f"release: {sorted(release_versions)})"
        )
    for name, version in release_versions.items():
        other = build_versions.get(name)
        if other and other != version:
            fail(
                f"{name} is @{version} in release.yml and @{other} in build.yml: the "
                "release would run different tooling from the build it promotes"
            )

    # 2 - the SDK step is the same step in both files.
    build_sdk, release_sdk = find(build_steps, SDK_STEP), find(release_steps, SDK_STEP)
    if build_sdk is None or release_sdk is None:
        fail(f"the '{SDK_STEP}' step is missing from one of the workflows")
    elif build_sdk.run != release_sdk.run:
        fail(
            f"'{SDK_STEP}' differs between the two workflows; a difference there is a "
            "release that fails where the build succeeded"
        )

    # 3 - what the build workflow reads, it also writes.
    build_body = "\n".join(step.run for step in build_steps)
    written: set[str] = set()
    for match in LOG_WRITE_RE.finditer(build_body):
        written.update(group for group in match.groups() if group)
    read = set(LOG_ANY_RE.findall(build_body))
    for name in sorted(read - written):
        fail(f"build.yml reads /tmp/{name} but no step writes it")
    for step in build_steps:
        if "gradlew" not in step.run:
            continue
        logs = set(LOG_ANY_RE.findall(step.run))
        if not logs:
            fail(
                f"'{step.label()}' runs Gradle without writing a log; a stage whose "
                "output nobody captures cannot be reported on"
            )
        for name in sorted(logs - read):
            fail(f"'{step.label()}' writes /tmp/{name} and nothing reads it")

    # 4 - who decides a failed build.
    for step in build_steps:
        if "gradlew" in step.run and "|| true" not in step.run:
            fail(
                f"'{step.label()}' runs Gradle without '|| true': the build workflow "
                "reports through the gate at the end, reading every log, and a step "
                "that dies first leaves the later ones unexplained"
            )
    for step in release_steps:
        if "gradlew" in step.run and "|| true" in step.run:
            fail(
                f"'{step.label()}' swallows a failure of the release build; a release "
                "has to fail loudly"
            )

    # 5 - a release publishes on a tag, and can be rehearsed.
    release_text = RELEASE.read_text(encoding="utf-8")
    triggers = release_text[: release_text.index("permissions:")] if "permissions:" in release_text else release_text
    if not re.search(r"^\s*tags:\s*$", triggers, re.MULTILINE) or '"v*"' not in triggers:
        fail("release.yml does not trigger on a version tag")
    if "workflow_dispatch" not in triggers:
        fail("release.yml cannot be rehearsed by hand")
    publishing = [step for step in release_steps if "gh release create" in step.run]
    if not publishing:
        fail("release.yml has no step that publishes a release")
    for step in publishing:
        condition = "\n".join(step.lines)
        if "refs/tags/v" not in condition:
            fail(f"'{step.label()}' publishes without being gated on a version tag")
    signing = find(release_steps, "Materialise the signing key if the secrets are present")
    if signing is None:
        fail("release.yml has no signing key step")
    elif "MEMORYMAP_KEYSTORE_B64" not in "\n".join(signing.lines):
        fail("the signing step does not read the keystore secret")

    if len(build_steps) < 20:
        fail(
            f"build.yml has {len(build_steps)} steps; this check is reading the file "
            "wrongly or the workflow lost its guards"
        )

    return report()


def report() -> int:
    if failures:
        print("workflow check: FAIL")
        for message in failures:
            print(f"  - {message}")
        return 1
    print(
        "workflow check: PASS (both workflows pin their actions and agree on the "
        "toolchain and the SDK step, every log the report reads is written, Gradle "
        "stages report through the gate rather than dying first, and only a version "
        "tag publishes)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
