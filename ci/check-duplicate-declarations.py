#!/usr/bin/env python3
"""Fail the build when two files declare the same top-level type or property.

This is the second mistake of its kind to cost a run: a test class was written twice,
once as a private helper at the bottom of an existing test file and once in a new one,
in the same package. Kotlin's answer is

    MemoriesViewModelTest.kt:224:15 Redeclaration:
    .../MemoryDetailViewModelTest.kt:151:32 Cannot access 'class FailingDeleteMemoryRepository'

which is a compile failure, so no test runs and the report says `TOTAL tests=0` - a red
run for something that is purely a matter of reading the tree, and something the person
writing the second class had no way to notice.

What counts as a collision, and why the rule is narrower than it sounds:

- **Top-level only.** A nested or inner class is scoped to its owner, so two owners may
  each have a `Builder` without anyone being confused.
- **Same source set and same package.** `app/src/main` and `app/src/test` are separate
  compilations, so a test double may share a name with the production class it stands in
  for - `androidTest` would be a third. Two different packages may each declare `Config`.
- **Types and properties, not functions.** Kotlin resolves overloads by parameter list, so
  two top-level `fun parse(String)` and `fun parse(Int)` are both legal and both wanted;
  deciding that correctly needs a real parser. Types cannot be overloaded, and neither can
  properties, so those are the two that can be checked without lying.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

PACKAGE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)

# A declaration at column zero is a top-level one. The kind is captured so a type and a
# property with the same name are not compared with each other, and the head is captured
# whole rather than just its first word, because a property may be an extension.
TOP_LEVEL = re.compile(
    r"^(?P<modifiers>(?:@\w+(?:\([^)]*\))?[ \t]*|"
    r"(?:open|abstract|sealed|data|enum|value|annotation|inner|private|internal|public|"
    r"protected|external|expect|actual|operator|infix|inline|suspend|const|lateinit)[ \t]*)*)"
    r"(?P<kind>class|interface|object|val|var)[ \t]+(?P<head>[^\n:=]+)",
    re.MULTILINE,
)

TYPE_KINDS = ("class", "interface", "object")
PROPERTY_KINDS = ("val", "var")


def type_name(head: str) -> str:
    """The name a type declares, without the generics that may follow it."""
    match = re.match(r"([A-Za-z_]\w*)", head.strip())
    return match.group(1) if match else ""


def property_name(head: str) -> str:
    """The name a property declares, receiver included when it is an extension.

    `val CameraFlash.icon` declares `icon` on `CameraFlash`, so the receiver is part of
    what identifies it: several extension properties may share a name as long as they
    extend different things. The first version of this guard read `CameraFlash` as the
    name and reported three of those as collisions that do not exist.
    """
    declared = re.split(r"\s*[:=(]", head.strip(), maxsplit=1)[0].strip()
    pieces = declared.split(".")
    if len(pieces) == 1:
        return pieces[0].strip()
    receiver = ".".join(pieces[:-1]).strip()
    return f"{receiver}.{pieces[-1].strip()}"


def declarations(path: pathlib.Path):
    """Every top-level declaration in the file, as (package, kind, name, line)."""
    text = path.read_text(encoding="utf-8")
    package = PACKAGE.search(text)
    package_name = package.group(1) if package else ""
    for match in TOP_LEVEL.finditer(text):
        kind = match.group("kind")
        head = match.group("head")
        if kind in TYPE_KINDS:
            name = type_name(head)
        elif kind in PROPERTY_KINDS:
            name = property_name(head)
        else:
            continue
        if not name:
            continue
        yield package_name, kind, name, text[:match.start()].count("\n") + 1


def source_set(path: pathlib.Path) -> str:
    """Which compilation a file belongs to: `main`, `test`, or its own directory name."""
    parts = path.relative_to(ROOT / "app/src").parts
    return parts[0]


def main() -> int:
    if not all(source.is_dir() for source in SOURCES):
        print(f"the sources are not where this guard expects them: {SOURCES}")
        return 1

    seen: dict[tuple[str, str, str], tuple[pathlib.Path, int]] = {}
    problems = []
    checked = 0

    for source in SOURCES:
        for path in sorted(source.rglob("*.kt")):
            for package, kind, name, line in declarations(path):
                if kind in PROPERTY_KINDS:
                    key_kind = "property"
                elif kind in TYPE_KINDS:
                    key_kind = "type"
                else:
                    continue
                checked += 1
                key = (source_set(path), package, f"{key_kind} {name}")
                if key in seen:
                    first_path, first_line = seen[key]
                    problems.append(
                        f"{path.relative_to(ROOT)}:{line}: {kind} {name} is already declared "
                        f"in {first_path.relative_to(ROOT)}:{first_line} "
                        f"(same source set, same package {package or '<root>'})"
                    )
                else:
                    seen[key] = (path, line)

    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} declaration(s) that Kotlin would refuse")
        return 1

    print(f"OK - {checked} top-level declarations across {len(SOURCES)} source sets, no name "
          f"declared twice in one package")
    return 0


if __name__ == "__main__":
    sys.exit(main())
