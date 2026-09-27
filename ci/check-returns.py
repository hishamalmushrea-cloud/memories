#!/usr/bin/env python3
"""Fail the build when a block-bodied function can never return its value.

Kotlin only returns the last expression of a function when it is written as an
expression body (`fun f(): Int = ...`). In a block body the value of the last
statement is thrown away, and the compiler says `Missing return statement` - one
line, in a CI run that costs a push. That is exactly how the account-deletion
round failed: a `runCatching { ... }.fold(...)` chain was left as the last
statement of a function declaring `AuthRepository.Deletion`, which a reader sees
as a return and Kotlin does not.

So this looks for one shape: a function with a declared, non-`Unit` return type,
a block body, and a last statement that cannot leave the function. The last
statement of a body is walked back to its own beginning rather than read off the
last line - a `return` whose value spans four lines ends on `.isNotEmpty()`, and
a call chain with lambdas in it ends on a closing parenthesis either way - and
then it must start with one of:

    return (not return@)   throw   error(   TODO(   check(   require(   ...
    if   when   try   else   while   for

A control-flow construct is accepted on its own: proving that every branch of an
`if` or a `when` returns is flow analysis, and this check errs towards silence,
because a guard that fails on valid code costs more than the mistake it prevents.
`Nothing` is exempt for the same reason.

Strings and comments are blanked out first, with the same care as
`ci/check-balance.py` and for the same reason: the word "return" appears in
comments and documentation, and a `}` inside a string is not a brace. See that
file's docstring for the traps - raw strings that end with a quote, and character
literals holding a bracket - which this walker repeats rather than imports, so a
change in one cannot silently change the other.

What it cannot see: types, a return type written across a line break (that
function is skipped), whether the returned value is the right one, and whether
every branch of a construct really returns. Those are still the compiler's.

Run from the repository root: python3 ci/check-returns.py
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

# `fun name(` - whatever modifiers are in front of it are not the question. A
# name in angle brackets is a type parameter, which is not a function.
FUNCTION = re.compile(r"\bfun\s+(?:<[^>]*>\s+)?([A-Za-z_]\w*)\s*\(")

# A return type and a block body, on one line. Deliberately not `\s` between the
# parts: `\s` crosses line breaks, and with it `fun syncMetaDao(): SyncMetaDao`
# followed by a blank line and `companion object {` reads as one function whose
# return type is "SyncMetaDao companion object".
BLOCK_BODY = re.compile(r"[ \t]*:[ \t]*([\w.<>?,\[\]]+)[ \t]*\{")

# What a statement can start with and still be a way of leaving the function.
# What a line can end with when the next line continues it: `return a ||` then
# `b` is one statement, and reading `b` as the body's last would be wrong.
CONTINUES = set("|&+-*/%.=<>!,([{:?\\")

LEAVES = re.compile(
    r"^(return(?!@)\b|throw\b|error\s*\(|TODO\s*\(|check\s*\(|checkNotNull\s*\(|"
    r"require\s*\(|requireNotNull\s*\(|exitProcess\s*\(|if\b|when\b|try\b|else\b|"
    r"while\b|for\b|do\b)"
)

# A body holding one of these can diverge anywhere in it, and chasing where is
# not this check's job.
DIVERGES = re.compile(r"\bthrow\b|\berror\s*\(|\bTODO\s*\(|while\s*\(\s*true\s*\)")

def blank_out(text: str) -> str:
    """Replace comments and string literals with spaces, keeping every offset."""
    result = list(text)
    index = 0
    length = len(text)

    def blank(start: int, end: int) -> None:
        for position in range(start, min(end, length)):
            if result[position] != "\n":
                result[position] = " "

    while index < length:
        char = text[index]
        pair = text[index:index + 2]
        triple = text[index:index + 3]

        if pair == "//":
            end = text.find("\n", index)
            end = length if end < 0 else end
            blank(index, end)
            index = end
        elif pair == "/*":
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            blank(index, end)
            index = end
        elif triple == '"""':
            end = text.find('"""', index + 3)
            if end < 0:
                return text
            # A raw string can end with a quote of its own, written as a run of
            # four; the string ends at the end of the run.
            run = end
            while run < length and text[run] == '"':
                run += 1
            blank(index, run)
            index = run
        elif char == '"':
            end = index + 1
            while end < length and text[end] != '"':
                end += 2 if text[end] == "\\" else 1
            blank(index, end + 1)
            index = end + 1
        elif char == "'":
            # One character or an escape is a literal; anything else starting
            # with a single quote is an apostrophe inside a name.
            end = index + 1
            while end < length and text[end] == "\\":
                end += 2
            end += 1
            if text[end:end + 1] == "'" and (end - index) <= 3:
                blank(index, end + 1)
                index = end + 1
            else:
                index += 1
        else:
            index += 1
    return "".join(result)


def matching(text: str, start: int, opener: str, closer: str) -> int:
    """The index of the brace/bracket that closes the one at [start], or -1."""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    return -1


def last_statement(blank: str, raw: str) -> str:
    """The last statement of a body, walked back to its own beginning.

    `blank` is the body with strings and comments blanked out, which is what the
    bracket counting needs; `raw` is the same body as written, which is what the
    boundary test needs. A line break is a boundary only when what follows it
    begins with a name and the line above it ends in something a statement can
    end in: `return a ||\n b` must not be cut in two, with `b` read as the body's
    last statement.

    The character before the line break is read from `raw` on purpose. In `blank`
    the string on the line above has become spaces, so the walk steps past it and
    lands on an `=` two tokens into the *previous* statement - which is how
    `TestDoubles.deleteLocalData`, a function ending in `return wipeSummary`, was
    once reported as a function that never returns.
    """
    depth = 0
    collected = []

    for index in range(len(blank) - 1, -1, -1):
        char = blank[index]
        if char in ")]}":
            depth += 1
        elif char in "([{":
            # Only the bracket count: an opener further back belongs to the
            # statement *before* the one being walked, and it says nothing about
            # where this one starts.
            depth -= 1
        elif depth == 0 and char in ";\n":
            tail = "".join(reversed(collected)).strip()
            if tail and (tail[0].isalpha() or tail[0] == "_"):
                before = index - 1
                while before >= 0 and raw[before] in " \t":
                    before -= 1
                # A line ending in one of these is continued by the next one, so
                # the break is not where a statement starts. A line ending in
                # anything else - a closing bracket, a quote, a name - is.
                if before < 0 or raw[before] not in CONTINUES:
                    return tail
        collected.append(char)

    return "".join(reversed(collected)).strip()


def scan(source: pathlib.Path) -> tuple:
    """The problems in one file, and how many functions were actually examined."""
    original = source.read_text(encoding="utf-8")
    text = blank_out(original)
    problems = []
    examined = 0

    for match in FUNCTION.finditer(text):
        # The parameter list, balanced: a lambda parameter holds parentheses of
        # its own - `block: () -> Unit` - which a plain `[^)]*` would stop at.
        open_paren = text.index("(", match.start(1) + len(match.group(1)))
        close_paren = matching(text, open_paren, "(", ")")
        if close_paren < 0:
            continue

        declared = BLOCK_BODY.match(text, close_paren + 1)
        if declared is None:
            continue
        return_type = " ".join(declared.group(1).split())
        if return_type.startswith("Unit") or "Nothing" in return_type:
            continue

        body_start = declared.end() - 1
        body_end = matching(text, body_start, "{", "}")
        if body_end < 0:
            continue
        body = text[body_start + 1:body_end]
        raw_body = original[body_start + 1:body_end]
        examined += 1

        if DIVERGES.search(body):
            continue
        if LEAVES.match(last_statement(body, raw_body)):
            continue

        line = text.count("\n", 0, match.start()) + 1
        problems.append(
            f"{source.relative_to(ROOT)}:{line}: {match.group(1)} declares "
            f"{return_type} and its last statement cannot leave the function - "
            f"a block body needs an explicit `return`"
        )

    return problems, examined


# The number of functions this check has to find to be believable. A matcher that
# reads nothing passes every test written against it, and the first version of
# this file did exactly that - an anchored pattern that never matched, and a run
# that printed OK. So the count is asserted rather than admired: the tree held
# 129 functions with a declared return type and a block body when this floor was
# set, out of 1030 `fun` declarations (373 of them Unit, 120 expression bodies,
# and the rest declarations in interfaces and DAOs with no body at all).
EXAMINED_FLOOR = 100


def main() -> int:
    problems = []
    files = 0
    examined = 0
    for source_root in SOURCES:
        for source in sorted(source_root.rglob("*.kt")):
            files += 1
            found, count = scan(source)
            problems.extend(found)
            examined += count

    for problem in problems[:40]:
        print(problem)
    if problems:
        print(f"return check: FAIL ({len(problems)} functions with no return)")
        return 1

    if examined < EXAMINED_FLOOR:
        print(
            f"return check: FAIL - only {examined} functions were examined, below the "
            f"{EXAMINED_FLOOR} this check is known to find; the matcher is broken"
        )
        return 1

    print(
        f"OK - every block-bodied function that declares a value returns it "
        f"({examined} functions across {files} Kotlin files)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
