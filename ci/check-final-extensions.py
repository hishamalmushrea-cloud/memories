#!/usr/bin/env python3
"""Fail the build when a class extends another class that is final.

This repository has no local compiler: CI is the only thing that turns Kotlin into an
answer, and a run costs minutes. One of the two mistakes that cost a run was exactly
this - a test subclassed `FailableReferenceRepository`, which is a plain `class`, and
the report that came back said only:

    OrganizationViewModelTest.kt:171:9 This type is final, so it cannot be extended.

Kotlin makes every class final unless it is declared `open`, `abstract` or `sealed`. The
rule is mechanical, the declarations are all in this repository, and the failure mode is
a red run rather than a subtle bug, so it belongs in a guard rather than in a run.

Reading a Kotlin class header is the whole difficulty, and this file did it wrong twice
before it did it right:

- `^` without `re.MULTILINE` matches only the start of the file, so the first version
  read no declarations at all and passed every fault it was given.
- The first colon after a class name is not always the supertype colon. `class Foo
  @Inject constructor(\n private val config: Bar,\n)` has a colon inside its constructor,
  and a body-less `data class Existing(val updatedAt: String)` has one inside its
  parameter list; both were read as inheritance and both were reported as errors that
  did not exist.

So the parser now does what the compiler does: skip annotations and the constructor
(including the `constructor` keyword), and only then look at the next non-space
character. If it is not a colon, the class inherits from nothing and the search stops
there rather than wandering into the rest of the file.

The judgement stays conservative. When a simple name is declared more than once (a fake
in one test file and the real thing in another), the guard says nothing unless *every*
declaration of that name cannot be extended, because a guard that cries wolf is a guard
that gets deleted.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

MODIFIER_RE = re.compile(
    r"^[ \t]*(?P<modifiers>(?:@\w+(?:\([^)]*\))?[ \t]*|"
    r"(?:open|abstract|sealed|data|enum|value|annotation|inner|private|internal|public|"
    r"protected|external|expect|actual|companion)[ \t]*)*)"
    r"(?P<kind>class|interface|object)[ \t]+(?P<name>\w+)",
    # Every declaration starts on its own line, and without this `^` means "start of
    # file", which is how the first version of this guard read nothing and passed
    # everything.
    re.MULTILINE,
)

EXTENDABLE = ("open", "abstract", "sealed")

# Exemptions, keyed by "Subclass -> Superclass" exactly as the guard prints it. Empty,
# and meant to stay empty: if this ever needs one, the right answer is usually to mark
# the supertype `open` or to stop extending it.
ALLOWED: set[str] = set()


def declarations(text: str):
    """Every type declared in the file, as (name, whether it can be extended)."""
    for match in MODIFIER_RE.finditer(text):
        modifiers = match.group("modifiers")
        kind = match.group("kind")
        can = kind == "interface" or any(word in modifiers for word in EXTENDABLE)
        yield match.group("name"), can


def skip_balanced(text: str, index: int) -> int:
    """The index just past a balanced (...) / <...> group starting at [index]."""
    opening = text[index]
    closing = {"(": ")", "<": ">", "[": "]"}[opening]
    depth = 0
    while index < len(text):
        if text[index] == opening:
            depth += 1
        elif text[index] == closing:
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return index


def skip_whitespace(text: str, index: int) -> int:
    while index < len(text) and text[index] in " \t\r\n":
        index += 1
    return index


def skip_annotations_and_constructor(text: str, index: int) -> int:
    """Past every `@Annotation(...)`, the `constructor` keyword and its parameters."""
    while True:
        index = skip_whitespace(text, index)
        if index < len(text) and text[index] == "@":
            match = re.match(r"@[\w.]+", text[index:])
            if not match:
                return index
            index += match.end()
            index = skip_whitespace(text, index)
            if index < len(text) and text[index] == "(":
                index = skip_balanced(text, index)
            continue
        keyword = re.match(r"constructor\b", text[index:])
        if keyword:
            index += keyword.end()
            index = skip_whitespace(text, index)
            if index < len(text) and text[index] == "(":
                index = skip_balanced(text, index)
            continue
        if index < len(text) and text[index] == "(":
            index = skip_balanced(text, index)
            continue
        return index


def header_end(text: str, start: int) -> int:
    """Where the supertype list stops: the body brace, or the end of the declaration.

    A body-less class ends at its newline. The two continuations that are allowed to
    cross a line are an empty list so far (`class Foo :` on its own line) and a list
    whose next line starts with a comma.
    """
    depth = 0
    index = start
    while index < len(text):
        char = text[index]
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        elif depth == 0 and char == "{":
            return index
        elif depth == 0 and char == "\n":
            so_far = text[start:index].strip()
            following = text[index + 1:].lstrip(" \t")
            if so_far and not following.startswith(","):
                return index
        index += 1
    return len(text)


def supertypes(text: str):
    """Every inheritance this file declares, as (subclass, index, supertype name)."""
    for match in MODIFIER_RE.finditer(text):
        if match.group("kind") != "class":
            continue
        index = skip_annotations_and_constructor(text, match.end())
        if index >= len(text) or text[index] != ":":
            # No colon where a supertype list would be, so this class inherits from
            # nothing. Anything further into the file belongs to another declaration.
            continue
        header = text[index + 1:header_end(text, index + 1)]
        for entry in split_top_level(header):
            name = re.match(r"\s*([A-Za-z_][\w.]*)", entry)
            if name:
                yield match.group("name"), match.start(), name.group(1).rsplit(".", 1)[-1]


def split_top_level(text: str):
    """Split a supertype list on the commas that are not inside generics or parens."""
    parts, depth, current = [], 0, ""
    for char in text:
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    if current.strip():
        parts.append(current)
    return parts


def main() -> int:
    if not all(source.is_dir() for source in SOURCES):
        print(f"the sources are not where this guard expects them: {SOURCES}")
        return 1

    # Name -> True when any declaration of that name can be extended. Silence is the
    # safe answer for a name that is declared twice in different ways.
    extendable: dict[str, bool] = {}
    files = []
    for source in SOURCES:
        for path in sorted(source.rglob("*.kt")):
            files.append(path)
            for name, can in declarations(path.read_text(encoding="utf-8")):
                extendable[name] = extendable.get(name, False) or can

    problems = []
    used = set()
    for path in files:
        text = path.read_text(encoding="utf-8")
        for subclass, start, supertype in supertypes(text):
            if supertype not in extendable or extendable[supertype]:
                continue
            entry = f"{subclass} -> {supertype}"
            if entry in ALLOWED:
                used.add(entry)
                continue
            line = text[:start].count("\n") + 1
            problems.append(
                f"{path.relative_to(ROOT)}:{line}: {subclass} extends {supertype}, "
                f"which cannot be extended"
            )

    for entry in sorted(ALLOWED - used):
        problems.append(f"the exemption for {entry} no longer matches anything")

    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} inheritance(s) that Kotlin would refuse")
        return 1

    print(f"OK - {len(files)} Kotlin file(s) checked, every supertype that comes from "
          f"this repository can be extended")
    return 0


if __name__ == "__main__":
    sys.exit(main())
