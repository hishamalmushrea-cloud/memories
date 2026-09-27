#!/usr/bin/env python3
"""Check the Android string resources before aapt2 has to.

A resource string is not free text. A bare apostrophe in one is a build failure,
and the message aapt2 gives for it - "Invalid unicode escape sequence in string"
- names neither the character nor the reason; the two failing lines were the two
in the whole project that contained `device's`. That failure arrived from CI,
after a push, because the resource step is where it happens: nothing in
`./gradlew help`, the unit tests or the guard scripts looks at the XML first.

So this checks, without Gradle:

  * every locale file parses, and holds the same resource names - a string that
    exists in one locale and not the other is a screen that falls back, silently;
  * a plural has the same quantities in both, or Arabic loses `two` and `few`;
  * no text has a bare apostrophe, and every backslash starts an escape that
    Android actually accepts (`\\`, `\\'`, `\\"`, `\\n`, `\\t`, `\\uXXXX`);
  * the placeholders are identical per resource, so a translation cannot drop a
    `%1$d` and turn a formatted string into a crash at the one moment it is
    shown.

What it cannot check is anything about meaning, and it does not pretend to: a
translation can be wrong in both languages at once and this will pass.

Run from the repository root: python3 ci/check-strings.py
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES_DIR = Path("app/src/main/res")
# The locales the app ships. Adding one means adding it here and to
# `resourceConfigurations` in app/build.gradle.kts.
LOCALES = ["values", "values-en"]

# The plural categories each locale has to provide. Android falls back to
# `other` for a missing category, which is why a missing Arabic `few` is a bug
# and not a shortcut: 3 to 10 would be read out with the wrong wording.
PLURAL_CATEGORIES = {
    "values": {"zero", "one", "two", "few", "many", "other"},
    "values-en": {"one", "other"},
}

# Android's own escape characters. Anything else after a backslash is a failure,
# and a backslash at the end of the text is too.
VALID_ESCAPES = {"\\", "'", '"', "n", "t", "u", " ", "@", "?"}
ESCAPE = re.compile(r"\\(.)", re.DOTALL)
UNICODE_ESCAPE = re.compile(r"\\u[0-9a-fA-F]{4}")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]")

problems = []


def text_of(element: ET.Element) -> str:
    """The whole text of a resource, however it was written."""
    return "".join(element.itertext())


def check_text(where: str, text: str) -> None:
    for match in re.finditer(r"(?<!\\)'", text):
        problems.append(
            f"{where}: bare apostrophe at character {match.start()} - write \\' or wrap the text in quotes"
        )
    for match in ESCAPE.finditer(text):
        char = match.group(1)
        if char == "u":
            if not UNICODE_ESCAPE.match(text, match.start()):
                problems.append(f"{where}: a \\u escape needs four hex digits")
            continue
        if char == "\\" and UNICODE_ESCAPE.match(text, match.start()):
            continue
        if char not in VALID_ESCAPES:
            problems.append(f"{where}: unknown escape \\{char}")
    if text.endswith("\\"):
        problems.append(f"{where}: text ends with a backslash")


def collect(path: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """The strings and plurals of one locale, keyed by name."""
    root = ET.parse(path).getroot()
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    for child in root:
        if child.tag not in ("string", "plurals"):
            continue
        name = child.get("name")
        if child.tag == "string":
            strings[name] = text_of(child)
            check_text(f"{path}:{name}", strings[name])
        else:
            quantities = {}
            for item in child:
                quantity = item.get("quantity")
                quantities[quantity] = text_of(item)
                check_text(f"{path}:{name}[{quantity}]", quantities[quantity])
            plurals[name] = quantities
    return strings, plurals


def main() -> int:
    tables: dict[str, dict[str, str]] = {}
    plural_tables: dict[str, dict[str, dict[str, str]]] = {}

    for locale in LOCALES:
        path = RES_DIR / locale / "strings.xml"
        if not path.exists():
            problems.append(f"{path}: missing")
            continue
        try:
            tables[locale], plural_tables[locale] = collect(path)
        except ET.ParseError as error:
            problems.append(f"{path}: not valid XML - {error}")

    if len(tables) < 2:
        for problem in problems:
            print(problem)
        print("string check: FAIL")
        return 1

    first, *rest = LOCALES
    for locale in rest:
        missing = sorted(set(tables[first]) - set(tables[locale]))
        extra = sorted(set(tables[locale]) - set(tables[first]))
        if missing:
            problems.append(f"{locale}: {len(missing)} strings missing, first {missing[:5]}")
        if extra:
            problems.append(f"{locale}: {len(extra)} strings that {first} does not have, first {extra[:5]}")

        missing = sorted(set(plural_tables[first]) - set(plural_tables[locale]))
        extra = sorted(set(plural_tables[locale]) - set(plural_tables[first]))
        if missing:
            problems.append(f"{locale}: {len(missing)} plurals missing, first {missing[:5]}")
        if extra:
            problems.append(f"{locale}: {len(extra)} plurals that {first} does not have, first {extra[:5]}")

    # Every locale provides the categories its own language needs. Not the same
    # categories in both: English has no `few`, and Arabic has no excuse for
    # leaving one out.
    for locale in LOCALES:
        for name, quantities in plural_tables[locale].items():
            expected = PLURAL_CATEGORIES[locale]
            missing = sorted(expected - set(quantities))
            if missing:
                problems.append(f"{name}: {locale} is missing the {missing} categories")
            extra = sorted(set(quantities) - expected)
            if extra:
                problems.append(f"{name}: {locale} has {extra}, which its language does not use")

    for name, text in tables[first].items():
        expected = sorted(PLACEHOLDER.findall(text))
        for locale in rest:
            other = tables[locale].get(name)
            if other is None:
                continue
            actual = sorted(PLACEHOLDER.findall(other))
            if actual != expected:
                problems.append(
                    f"{name}: {first} takes {expected} and {locale} takes {actual}"
                )

    # A plural as a whole has to take the same arguments, not each category: the
    # Arabic `one` says "one attachment" without a number on purpose, which is
    # correct, and a rule per category would call that a defect.
    for name, quantities in plural_tables[first].items():
        expected = sorted({found for text in quantities.values() for found in PLACEHOLDER.findall(text)})
        for locale in rest:
            other = plural_tables[locale].get(name)
            if other is None:
                continue
            actual = sorted({found for text in other.values() for found in PLACEHOLDER.findall(text)})
            if actual != expected:
                problems.append(f"{name}: {first} takes {expected} and {locale} takes {actual}")

    for problem in problems[:40]:
        print(problem)
    if problems:
        print(f"string check: FAIL ({len(problems)} problems)")
        return 1

    count = sum(len(table) for table in tables.values())
    plural_count = sum(len(table) for table in plural_tables.values())
    print(
        f"string check: PASS ({count} strings and {plural_count} plurals across "
        f"{len(LOCALES)} locales, no bare apostrophes, placeholders aligned)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
