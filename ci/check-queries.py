#!/usr/bin/env python3
"""Fail the build on a Room `@Query` that is not the query it looks like.

Two mistakes in this file's history are invisible to the Kotlin compiler:

1. A `@Query` whose SQL names a parameter the function never declares. Room
   rejects that at build time, but only once the annotation processor runs.
2. A `+` dropped between two adjacent string literals. Kotlin does not join
   them, so the query is silently truncated and the first fragment is the whole
   query. Seven of these once shipped at once: two failed loudly, five compiled
   happily and lost their `ORDER BY` and their `deleted_at IS NULL` clauses,
   which would have returned deleted rows.

Both are checked here so neither costs a ten-minute build cycle again.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DAO_ROOT = ROOT / "app/src/main/java/com/memorymap/data/local/dao"

QUOTE = '"'


def mask_strings(text):
    """Blank the contents of every string literal, keeping both its delimiters.

    Only the opening and closing quotes survive, one each: a raw string's
    triple quote collapses to a single one. That makes the quotes alternate
    open, close, open, close, so the check below can tell where a literal ends
    and what stands between it and the next one. Offsets are preserved, so a
    reported line number is the real one.
    """
    triple = QUOTE * 3
    out = list(text)

    def blank(low, high):
        for offset in range(max(low, 0), min(high, len(text))):
            if out[offset] != "\n":
                out[offset] = " "

    index, length = 0, len(text)
    while index < length:
        if text[index] != QUOTE:
            index += 1
            continue
        raw = text.startswith(triple, index)
        end = index + 1
        while end < length:
            if raw and text.startswith(triple, end):
                end += 3
                break
            if not raw and text[end] == QUOTE and text[end - 1] != "\\":
                end += 1
                break
            end += 1
        if raw:
            blank(index + 1, end - 3)   # the body
            blank(end - 2, end)         # all but the first quote of the closing triple
        else:
            blank(index + 1, end - 1)   # the body, keeping the closing quote
        index = end
    return "".join(out)


def unjoined_literals(masked, source):
    """A closing quote, then anything but a `+`, then an opening quote.

    The two literals may be separated by whitespace, a comment, or nothing at
    all, but not by `+`. Kotlin does not concatenate them, so the second one is
    discarded and the query is silently its first fragment alone.

    Quotes are paired rather than searched for: masking leaves exactly two per
    literal, so the closing quote of one is always at an odd index and the
    opening quote of the next follows it. Matching them by position is what
    keeps a single literal from looking like a pair.
    """
    positions = [i for i, char in enumerate(masked) if char == QUOTE]
    problems = []
    for start in range(1, len(positions) - 1, 2):
        closing, opening = positions[start], positions[start + 1]
        between = masked[closing + 1: opening]
        between = re.sub(r"//[^\n]*", "", between)
        between = re.sub(r"/\*(?:.|\n)*?\*/", "", between)
        if between.strip() == "":
            problems.append(source[:closing].count("\n") + 1)
    return problems


def main():
    problems = 0
    checked = 0
    for path in sorted(DAO_ROOT.glob("*.kt")):
        source = path.read_text()
        masked = mask_strings(source)

        for line in unjoined_literals(masked, source):
            print(
                f"{path.name}:{line}: two string literals with no `+` between them; "
                f"Kotlin will not join them, so the query is truncated"
            )
            problems += 1

        for match in re.finditer(r"@Query\s*\(", masked):
            depth, cursor = 0, match.end() - 1
            while cursor < len(masked):
                if masked[cursor] == "(":
                    depth += 1
                elif masked[cursor] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                cursor += 1

            sql = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', source[match.end():cursor]))
            following = re.search(r"\bfun\s+(\w+)\s*\(([^)]*)\)", masked[cursor:])
            if not following:
                print(f"{path.name}: a @Query with no function after it")
                problems += 1
                continue
            checked += 1
            name, params = following.group(1), following.group(2)
            used = set(re.findall(r":(\w+)", sql))
            declared = set(re.findall(r"(\w+)\s*:", params))
            if used != declared:
                print(
                    f"{path.name}.{name}: the SQL names {sorted(used)} but the function "
                    f"declares {sorted(declared)}"
                )
                problems += 1

    if problems:
        print(f"{problems} problem(s) across {checked} queries")
        return 1
    print(
        f"OK - {checked} queries each use exactly their own parameters, "
        f"and no two literals stand unjoined"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
