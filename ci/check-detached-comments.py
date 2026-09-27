#!/usr/bin/env python3
"""Fail the build when a comment separates a modifier from what it modifies.

Kotlin is happy to read this:

    @Singleton
    open /**
     * ... a documented block a patch spliced in ...
     */
    private const val THING = "..."

The `open` and the annotation are now attached to the wrong declaration, and the
compiler rejects it - `Modifier 'open' is not applicable to 'top level property'`
- one full build cycle later. It reads as valid code in review, which is the
whole problem.

The two signatures this looks for are a comment opening on the same line as
modifiers (`open /**`), and an annotation followed by a comment before anything
declares a name. Both come from anchoring a patch on a substring of a
declaration instead of on the whole declaration.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = (ROOT / "app/src/main/java", ROOT / "app/src/test/java")

MODIFIERS = (
    r"open|abstract|sealed|data|enum|annotation|const|private|internal|public"
    r"|override|suspend|inline|operator|infix|lateinit|vararg|external|final"
    r"|companion|value|actual|expect"
)


def blank_strings(text: str) -> str:
    """Replace the contents of every string literal with spaces.

    A URL holds `//` and a raw string can hold `/*`, and neither is a comment.
    Blanking them first means the scanner below only ever sees code.
    """
    out = list(text)
    index, length = 0, len(text)
    while index < length:
        char = text[index]
        if char == '"' and text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end == -1 else end + 3
            for k in range(index, end):
                if out[k] != "\n":
                    out[k] = " "
            index = end
        elif char in '"\'':
            k = index + 1
            while k < length and text[k] != char and text[k] != "\n":
                k += 2 if text[k] == "\\" else 1
            k = min(k + 1, length)
            for j in range(index, min(k, length)):
                if out[j] != "\n":
                    out[j] = " "
            index = k
        else:
            index += 1
    return "".join(out)


def comment_prefix(line: str):
    """Text before a comment that starts on this line, or None if none does."""
    match = re.match(r"^[^/*]*?(/\*|//)", line)
    return None if not match else line[: match.start(1)]


def is_bare_comment_start(line: str) -> bool:
    """True when the line opens a comment and only modifiers precede it."""
    prefix = comment_prefix(line)
    if prefix is None:
        return False
    # Anything else before the comment is code, which makes it a trailing one.
    return all(re.fullmatch(r"@[\w.]+|" + MODIFIERS, token) for token in prefix.split())


def opens_a_declaration(line: str) -> bool:
    """True when the line is an annotation, or is nothing but modifiers."""
    if re.match(r"^\s*@\w", line):
        return True
    return bool(re.fullmatch(r"\s*" + MODIFIERS + r"(?:\s+" + MODIFIERS + r")*\s*", line))


def main() -> int:
    problems = 0
    for root in SOURCES:
        for source in sorted(root.rglob("*.kt")):
            lines = blank_strings(source.read_text()).split("\n")
            for index, line in enumerate(lines):
                prefix = comment_prefix(line)
                # Only modifiers before the comment: `val x = 1 // note` is a
                # trailing comment and perfectly ordinary.
                if prefix is not None and prefix.strip() and is_bare_comment_start(line):
                    print(
                        f"{source.relative_to(ROOT)}:{index + 1}: a comment opens on the same "
                        f"line as `{prefix.strip()}`: {line.strip()[:60]}"
                    )
                    problems += 1
                    continue
                if not opens_a_declaration(line):
                    continue
                following = index + 1
                while following < len(lines) and not lines[following].strip():
                    following += 1
                if following < len(lines) and is_bare_comment_start(lines[following]):
                    problems += 1
                    print(
                        f"{source.relative_to(ROOT)}:{index + 1}: a comment separates "
                        f"`{line.strip()[:40]}` from what it modifies"
                    )
    if problems:
        print(f"{problems} detached declaration(s)")
        return 1
    print("OK - no comment separates a modifier from what it modifies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
