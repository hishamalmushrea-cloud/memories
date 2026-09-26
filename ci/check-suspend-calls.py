#!/usr/bin/env python3
"""Fail the build on a plain function that calls a suspend DAO method.

Kotlin only allows a suspend function to be called from a coroutine or from
another suspend function. A plain `fun` is neither, so the build fails at
compile time - long after this check could have caught it in a second. It has
cost three full CI cycles already, which is why it lives here.

The check is deliberately narrow. It only looks at block-bodied functions that
are not themselves suspend, and only at calls whose receiver is a DAO, because
suspend names collide with domain helpers (`DailyEntryDao.onThisDay` versus
`DiaryTime.onThisDay`). Calls inside a coroutine-builder lambda are skipped:
`fun x() = runTest { dao.upsert(..) }` is legal, the lambda is the coroutine.
"""
import re
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app/src/test/java"
DAO_ROOT = ROOT / "app/src/main/java/com/memorymap/data/local/dao"

BUILDERS = re.compile(
    r"\b(?:runTest|runBlocking|runBlockingTest|launch|async|withContext"
    r"|coroutineScope|supervisorScope|test)\s*[\({]"
)
FUN_HEADER = re.compile(r"\s*(?:private |internal |public |override )*(suspend )?fun ")


def suspend_dao_names() -> set:
    names = set()
    for dao in sorted(DAO_ROOT.glob("*.kt")):
        for match in re.finditer(r"^\s*suspend fun (\w+)", dao.read_text(), re.M):
            names.add(match.group(1))
    return names


def dao_calls(names: set) -> re.Pattern:
    alternation = "|".join(sorted(map(re.escape, names)))
    return re.compile(r"[Dd]ao\s*(?:\(\))?\s*\.\s*(%s)\s*\(" % alternation)


def block_body(lines: list, start: int) -> str:
    """The brace-balanced body of the function declared at `start`."""
    depth, body, started = 0, [], False
    for line in lines[start:]:
        depth += line.count("{") - line.count("}")
        if "{" in line:
            started = True
        body.append(line)
        if started and depth <= 0:
            break
    return "\n".join(body)


def outside_coroutines(text: str) -> str:
    """Drop everything inside a coroutine-builder lambda; those calls are legal."""
    kept, cursor = [], 0
    while cursor < len(text):
        builder = BUILDERS.search(text, cursor)
        kept.append(text[cursor : builder.start() if builder else len(text)])
        if not builder:
            break
        cursor, depth = builder.end(), 1
        while cursor < len(text) and depth > 0:
            depth += (text[cursor] == "{") - (text[cursor] == "}")
            cursor += 1
    return "".join(kept)


def main() -> int:
    names = suspend_dao_names()
    call = dao_calls(names)
    problems = 0
    for source in sorted(TEST_ROOT.rglob("*.kt")):
        lines = source.read_text().split("\n")
        for index, line in enumerate(lines):
            header = FUN_HEADER.match(line)
            if not header or header.group(1) or "{" not in line:
                continue
            for hit in call.finditer(outside_coroutines(block_body(lines, index))):
                problems += 1
                print(
                    f"{source.relative_to(ROOT)}:{index + 1}: "
                    f"a plain fun calls suspend `{hit.group(1)}`; "
                    f"mark the function suspend or move the call into a coroutine"
                )
    if problems:
        print(f"{problems} call site(s) would not compile")
        return 1
    print(f"OK - no plain function calls a suspend DAO method ({len(names)} known)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
