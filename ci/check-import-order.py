#!/usr/bin/env python3
"""Check the imports of every Kotlin file before the compiler has to.

Two shapes, both of which have already happened in this repository:

1. An `import` after a declaration.

    Kotlin only allows imports at the top of a file, before anything is declared:

        import io.github.jan.supabase.storage.storage

        /** Small enough that a batch stays a reasonable request. */
        private const val BATCH_SIZE = 100
        import javax.inject.Inject          // Syntax error: imports are only allowed
                                            // in the beginning of file.

   This shape comes from anchoring a patch on an import and appending to it, and it
   reads perfectly well until the compiler looks at it a build cycle later.

2. The same import twice.

    `app/src/test/java/com/memorymap/testing/TestDoubles.kt` imported
    `com.memorymap.domain.model.User` at line 9 and again at line 27, and nothing
    said so: the file compiles, the tests pass, and the duplicate is only found by
    a person reading the import block. The Kotlin compiler reports an unused
    import, but not this one, and lint does not look at it either.

Both are the same family of mistake as the rest of the checks in this directory:
a patch that splices something valid-looking somewhere it does not belong.

    python3 ci/check-import-order.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

# A line that is only part of a block comment, so it declares nothing.
COMMENT_BODY = re.compile(r"^\s*(?:\*|/\*|//)")

# The scanner reads what it understands; if it sees fewer imports than this, it is
# broken rather than the repository being clean. Its first version matched every
# Kotlin file and reported OK, having examined nothing.
EXAMINED_FLOOR = 2500


def scan(source: pathlib.Path) -> tuple[list[int], dict[str, list[int]]]:
    """Return the misplaced imports and the imports seen twice, per file."""
    misplaced: list[int] = []
    imports: dict[str, list[int]] = {}
    declared = False
    in_block_comment = False
    for number, line in enumerate(source.read_text(encoding="utf-8").split("\n"), start=1):
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
                misplaced.append(number)
            imported = " ".join(text[len("import "):].split())
            imports.setdefault(imported, []).append(number)
            continue
        declared = True
    return misplaced, imports


def main() -> int:
    problems = 0
    files = 0
    examined = 0
    for root in SOURCES:
        for source in sorted(root.rglob("*.kt")):
            files += 1
            misplaced, imports = scan(source)
            examined += sum(len(lines) for lines in imports.values())
            for number in misplaced:
                print(
                    f"{source.relative_to(ROOT)}:{number}: an import after a declaration; "
                    f"Kotlin only allows them at the beginning of the file"
                )
                problems += 1
            for imported, lines in imports.items():
                if len(lines) < 2:
                    continue
                where = ", ".join(str(line) for line in lines)
                print(
                    f"{source.relative_to(ROOT)}:{lines[0]}: `{imported}` is imported "
                    f"{len(lines)} times (lines {where})"
                )
                problems += 1

    if examined < EXAMINED_FLOOR:
        print(
            f"the scanner read {examined} imports, fewer than the {EXAMINED_FLOOR} "
            f"it is expected to see - it is broken, not the repository"
        )
        return 1
    if problems:
        print(f"{problems} import problem(s) across {files} files")
        return 1
    print(
        f"OK - {examined} imports across {files} Kotlin files come before every "
        f"declaration and none is repeated"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
