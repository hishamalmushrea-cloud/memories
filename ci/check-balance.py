#!/usr/bin/env python3
"""Fail the build when a Kotlin file is not structurally balanced.

The development sandbox for this repository has no JDK and no Android SDK, so a
mistake in a Kotlin file is otherwise first discovered by a CI run that takes
about fifteen minutes and produces one line saying "Expecting a top level
declaration". That is a bad trade for a check that runs in under a second.

This is not a parser and does not pretend to be one. It counts braces, brackets
and parentheses outside strings, characters and comments, and fails when a file
does not balance or when a count goes negative, which is what a spliced edit
looks like: a stray `}` closes a class early and every declaration after it
becomes a syntax error somewhere else entirely. It caught exactly that, once,
after the compiler did.

There are two traps this has to walk around, and both were found by running it.
A raw string that ends with a quote is written as a run of four quotes, so the
string ends at the end of the run rather than at its start; reading it as three
leaves a quote behind that swallows the rest of the file. And a character literal
holding a bracket - `source.indexOf('{')` - is real code that a check ignoring
single quotes would count as a brace. A literal is one character or an escape;
anything else starting with a single quote is an apostrophe in a name, like
"another account's records".

What it cannot see is anything else: types, names, arity, ordering. Those still
need the compiler, and the compiler still runs on every push.
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

PAIRS = {"{": "}", "[": "]", "(": ")"}
CLOSERS = {value: key for key, value in PAIRS.items()}


def scan(source: pathlib.Path) -> list:
    """The lines where this file's structure goes wrong, if it does."""
    text = source.read_text(encoding="utf-8")
    problems = []
    stack = []
    line = 1
    index = 0
    length = len(text)

    while index < length:
        char = text[index]
        pair = text[index:index + 2]
        triple = text[index:index + 3]

        if char == "\n":
            line += 1
            index += 1
            continue

        # A comment, a string or a character literal can hold anything at all,
        # including the characters this check counts.
        if pair == "//":
            end = text.find("\n", index)
            index = length if end < 0 else end
            continue
        if pair == "/*":
            end = text.find("*/", index + 2)
            if end < 0:
                problems.append(f"line {line}: a block comment is never closed")
                break
            line += text.count("\n", index, end)
            index = end + 2
            continue
        if triple == '"""':
            # A raw string can end with a quote of its own, and Kotlin writes that
            # as a run of four - `...""""` - so the string ends at the end of the
            # run of quotes rather than at its start. Reading it as three leaves a
            # stray quote behind, which then swallows the rest of the file.
            end = text.find('"""', index + 3)
            if end < 0:
                problems.append(f"line {line}: a raw string is never closed")
                break
            run = end
            while run < length and text[run] == '"':
                run += 1
            line += text.count("\n", index, run)
            index = run
            continue

        # A character literal holds one character, or an escape. Anything else
        # beginning with a single quote is an apostrophe inside a name or a
        # comment, which is not punctuation this check should care about.
        if char == "'":
            escaped = index + 1 < length and text[index + 1] == "\\"
            closing = index + (3 if escaped else 2)
            if closing < length and text[closing] == "'":
                index = closing + 1
            else:
                index += 1
            continue
        if char == '"':
            start_line = line
            index += 1
            while index < length and text[index] != char:
                if text[index] == "\\":
                    index += 1
                elif text[index] == "\n":
                    line += 1
                index += 1
            if index >= length:
                problems.append(f"line {start_line}: a string is never closed")
                break
            index += 1
            continue

        if char in PAIRS:
            stack.append((char, line))
        elif char in CLOSERS:
            if not stack:
                problems.append(f"line {line}: '{char}' closes nothing")
            elif stack[-1][0] != CLOSERS[char]:
                opener, opener_line = stack[-1]
                problems.append(
                    f"line {line}: '{char}' closes '{opener}' opened on line {opener_line}"
                )
                stack.pop()
            else:
                stack.pop()
        index += 1

    for opener, opener_line in stack:
        problems.append(f"line {opener_line}: '{opener}' is never closed")
    return problems


def main() -> int:
    files = sorted(
        path
        for root in SOURCES
        for path in root.rglob("*.kt")
    )
    if not files:
        print("balance check: FAIL - no Kotlin files found")
        return 1

    failed = False
    for path in files:
        problems = scan(path)
        if problems:
            failed = True
            print(f"{path.relative_to(ROOT)}")
            for problem in problems:
                print(f"  {problem}")

    if failed:
        print("balance check: FAIL")
        return 1
    print(f"OK - every bracket in {len(files)} Kotlin files balances")
    return 0


if __name__ == "__main__":
    sys.exit(main())
