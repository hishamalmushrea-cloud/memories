#!/usr/bin/env python3
"""Fail the build when a document quotes a number that the repository no longer has.

Every document here makes numeric claims: how many tests there are, how many string
resources, how many Kotlin files, how many guards run before Gradle. Each one was true
when it was written and each one is the kind of thing that quietly stops being true -
`SPEC_COMPLIANCE.md` said 534 strings and 178 source files while the repository had 548
and 128, and `READINESS.md` said eleven checks while there were twelve. Nobody lies on
purpose; numbers just drift, and a document that is a deliverable cannot drift.

So the claims are checked the way the rest of this repository is checked: by reading the
repository. Each rule below names a document, a pattern that must appear in it, and the
measurement the number has to equal.

Two design decisions, both learned the hard way in this project:

- **A rule whose pattern is missing is a failure, not a skip.** Twice in this repository
  a guard passed every fault because its matcher never matched anything, and a guard that
  silently stops checking is worse than no guard. Rewording a claim therefore means
  updating the rule for it, deliberately.
- **A file is matched by name, and the pattern may not span it.** Every pattern is
  anchored with a literal phrase that only appears once, so a rewritten paragraph fails
  loudly instead of matching something unintended.

The measurements come from the same sources the other guards use, so this file cannot
disagree with them: `check-strings.py` counts what is parsed out of the XML, and the test
count is the number of `@Test` methods, which is what the CI report calls the test count.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SOURCES = ROOT / "app/src/main/java/com/memorymap"
TESTS = ROOT / "app/src/test"

DOCS = ROOT / "docs"


def string_counts():
    """Strings and plurals across every locale, counted as `check-strings.py` counts."""
    strings = plurals = 0
    for locale in sorted(p.name for p in RES.glob("values*")):
        path = RES / locale / "strings.xml"
        if not path.exists():
            continue
        root = ElementTree.parse(path).getroot()
        strings += sum(1 for child in root if child.tag == "string")
        plurals += sum(1 for child in root if child.tag == "plurals")
    return strings, plurals


def test_counts():
    """Test methods and the classes that hold them."""
    methods = 0
    classes = 0
    for path in sorted(TESTS.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        found = len(re.findall(r"^[ \t]*@Test\b", text, re.MULTILINE))
        methods += found
        if found:
            classes += 1
    return methods, classes


def source_files():
    """Kotlin files under the app's own package, which is what a reader counts."""
    return len(list(SOURCES.rglob("*.kt")))


def guard_files():
    """The `check-*.py` guards, without `check-schema.py`.

    The schema check is named separately wherever it is described, because it needs a
    live PostgreSQL, so counting it here would make every sentence about the others
    wrong by one.
    """
    return len([p for p in (ROOT / "ci").glob("check-*.py") if p.name != "check-schema.py"])


def rules():
    """(document, pattern, the numbers the pattern's groups must equal, description)."""
    tests, classes = test_counts()
    strings, plurals = string_counts()
    files = source_files()
    guards = guard_files()
    compliance = DOCS / "SPEC_COMPLIANCE.md"
    readiness = DOCS / "READINESS.md"
    return [
        (compliance, r"\*\*(\d+) اختبارًا في (\d+) صنفًا",
         [tests, classes], "the verified state line: tests and classes"),
        (compliance, r"(\d+) صنفًا \| \*\*(\d+) اختبارًا",
         [classes, tests], "the tests row of the compliance table"),
        (compliance, r"\((\d+) نصًا و(\d+) جمعًا\)",
         [strings, plurals], "the resources row: strings and plurals"),
        (compliance, r"\((\d+) ملف Kotlin",
         [files], "the app row: Kotlin files"),
        (readiness, r"\| Tests \| (\d+) unit tests",
         [tests], "the tests row of the readiness summary"),
        (readiness, r"\| Guards \| (eleven|twelve|thirteen|fourteen|\d+) `check-\*\.py` files",
         [guards], "the guards row of the readiness summary"),
    ]


WORDS = {"eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14}


def as_number(text: str) -> int:
    return WORDS.get(text, None) if not text.isdigit() else int(text)


def main() -> int:
    problems = []
    checked = 0
    for document, pattern, expected, description in rules():
        if not document.exists():
            problems.append(f"{document.name}: missing, so {description} cannot be checked")
            continue
        text = document.read_text(encoding="utf-8")
        found = list(re.finditer(pattern, text))
        if not found:
            problems.append(
                f"{document.name}: {description} is no longer where this guard looks for "
                f"it (pattern {pattern!r}) - update the claim or the rule"
            )
            continue
        if len(found) > 1:
            problems.append(
                f"{document.name}: {description} matches {len(found)} times, so the "
                f"pattern is not anchored tightly enough"
            )
            continue
        numbers = [as_number(group) for group in found[0].groups()]
        if not numbers or len(numbers) != len(expected):
            # A non-capturing group is the trap: `(?:...)` matches, captures nothing, and
            # the loop below then compares nothing while still counting the rule as
            # checked. The same shape as the guard that passed every fault because it
            # read no declarations - so it is a failure here, not a warning.
            problems.append(
                f"{document.name}: the rule for {description} captures {len(numbers)} "
                f"number(s) but expects {len(expected)}, so it would check nothing"
            )
            continue
        for index, (quoted, actual) in enumerate(zip(numbers, expected)):
            if quoted != actual:
                problems.append(
                    f"{document.name}: {description} says {quoted}, this repository has "
                    f"{actual}"
                )
        checked += 1

    for problem in problems:
        print(problem)
    if problems:
        print(f"document check: FAIL ({len(problems)} claims that are not true)")
        return 1

    print(f"document check: PASS ({checked} numeric claims agree with the repository)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
