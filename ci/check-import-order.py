#!/usr/bin/env python3
"""Fail the build when an `import` appears after a declaration.

Kotlin only allows imports at the top of a file, before anything is declared:

    import io.github.jan.supabase.storage.storage

    /** Small enough that a batch stays a reasonable request. */
    private const val BATCH_SIZE = 100
    import javax.inject.Inject          // Syntax error: imports are only allowed
                                        // in the beginning of file.

This shape comes from anchoring a patch on an import and appending to it, and it
reads perfectly well until the compiler looks at it a build cycle later. The
other checks in this directory cover the same family of mistake - a patch that
splices a block somewhere valid-looking but invalid.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

# A line that is only part of a block comment, so it declares nothing.
COMMENT_BODY = re.compile(r"^\s*(?:\*|/\*|//)")


def scan(source: pathlib.Path) -> list:
    """The lines of this file where an import follows a declaration."""
    found = []
    declared = False
    in_block_comment = False
    for number, line in enumerate(source.read_text().split("\n"), start=1):
        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
            continue
        if line.strip().startswith("/*"):
            if "*/" not in line:
                in_block_comment = True
            continue

        text = line.strip()
        if not text or COMMENT_BODY.match(line):
            continue
        if text.startswith("package ") or text.startswith("@file:"):
            continue
        if text.startswith("import "):
            if declared:
                found.append(number)
            continue
        declared = True
    return found


def main() -> int:
    problems = 0
    files = 0
    for root in SOURCES:
        for source in sorted(root.rglob("*.kt")):
            files += 1
            for number in scan(source):
                print(
                    f"{source.relative_to(ROOT)}:{number}: an import after a declaration; "
                    f"Kotlin only allows them at the beginning of the file"
                )
                problems += 1
    if problems:
        print(f"{problems} misplaced import(s) across {files} files")
        return 1
    print(f"OK - imports come before every declaration in all {files} Kotlin files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
