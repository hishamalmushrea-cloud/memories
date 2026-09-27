#!/usr/bin/env python3
"""Fail the build when a document says something the repository no longer agrees with.

Two families of claim are checked: numbers, and the paths a document points a reader at.

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

The second family is the paths: every `path/to/Something.kt` a document names has to
resolve, because a document that sends a reader to a file that moved is wrong in the way
that wastes the most time.

The third family is the names a person has to create by hand: the repository secrets.
`docs/RELEASE.md` tells a maintainer which four secrets to add before a release will be
signed with their own key. If the document and the workflow disagree, nothing fails - the
release builds, signs with the debug key and publishes a bundle Play will refuse, and the
person reading the instructions has done nothing wrong. Both directions are checked: a
secret a workflow reads must be documented, and a name the document tells you to create
must be read by something (a workflow secret, or a Gradle property the build reads). A claim resolves when that exact path exists, or when a file
of that name exists anywhere in the repository - the second is what lets a document say
`AndroidManifest.xml` without spelling out its directory. Two names are exempt because
they are the reader's own files rather than the repository's, and the exemptions are
checked for staleness like everything else here.
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


def guarantees():
    """The guarantees the database check makes: the SQL's own, plus the checker's two.

    The SQL prints one `ok:` line per guarantee it confirms, and `check-schema.py` prints
    two of its own (that every table the client names exists, and every function it calls).
    Both are counted here so the summary's claim is derived rather than remembered.
    """
    sql = (ROOT / "supabase/verify/10_checks.sql").read_text(encoding="utf-8")
    printed = len(re.findall(r"\\echo 'ok: ", sql))
    checker = (ROOT / "ci/check-schema.py").read_text(encoding="utf-8")
    extra = len(re.findall(r'print\(f"ok: ', checker))
    return printed + extra


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
    # Built after WORDS is defined, because the pattern is derived from it.
    checks = guarantees()
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
        (readiness, rf"\| Guards \| ({NUMBER_WORD}) `check-\*\.py` files",
         [guards], "the guards row of the readiness summary"),
        (readiness, r"passes (\d+) checks on a real PostgreSQL",
         [checks], "the database row of the readiness summary"),
    ]


# The numbers a summary may spell out instead of writing in digits. One table, used both
# to read a claim and to build the pattern that finds it: the rule for the guards row used
# to enumerate the words itself, so it stopped matching the day the count grew past the
# last word in the list - the same kind of staleness this guard exists to catch.
WORDS = {
    "ten": 10,
    "eleven": 11,
    "twelve": 12,
    "thirteen": 13,
    "fourteen": 14,
    "fifteen": 15,
    "sixteen": 16,
    "seventeen": 17,
    "eighteen": 18,
    "nineteen": 19,
    "twenty": 20,
}
NUMBER_WORD = "|".join([*WORDS, r"\d+", r"[a-z]+"])

# A path claim: something inside backticks that ends in a file extension this repository
# uses. Directories (`ui/theme/`) and globs are not path claims and are left alone.
PATH_CLAIM = re.compile(
    r"`([A-Za-z0-9_./-]+\.(?:kt|kts|py|sh|sql|xml|md|json|toml|yml|properties|png|txt))`"
)
EXTENSIONS = ("kt", "kts", "py", "sh", "sql", "xml", "md", "json", "toml", "yml",
              "properties", "png", "txt")

# Files a document names that are not supposed to be in the repository, with the reason.
# `local.properties` is the developer's own and gitignored; `manifest.json` is written
# into a backup archive the reader creates, so it exists on their disk and not here.
NOT_IN_THE_REPOSITORY = {
    "local.properties": "the developer's own file, gitignored on purpose",
    "manifest.json": "written into a backup archive by the app, never committed",
}

# The documents that describe what the repository *is*, not what it has been. The
# changelog is deliberately not here: it describes bugs, and a bug is often a name that
# should never have existed - it records that an export once produced `manifest.json.json`
# and that a backup contains `memories.json` and its siblings, and neither is a file this
# guard could ever find. Checking history for present-day facts is the wrong question.
DOCUMENTS = ("README.md", "docs/SPEC_COMPLIANCE.md", "docs/READINESS.md", "docs/RELEASE.md",
             "docs/MANUAL_QA.md", "docs/SERVICE_LIMITS.md", "docs/PLATFORM_UPGRADE.md")


def as_number(text: str) -> int | None:
    """The value of a claim's number, or None when it is a word this table has never met."""
    if text.isdigit():
        return int(text)
    return WORDS.get(text)


def path_claims():
    """Every path-looking claim in the documents, as (document, name, line number)."""
    for name in DOCUMENTS:
        path = ROOT / name
        if not path.exists():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for match in PATH_CLAIM.finditer(line):
                yield name, match.group(1), number


def resolves(token: str) -> bool:
    """Whether a document's path claim points at something that exists."""
    if (ROOT / token).exists():
        return True
    # A document may name a file without its directory (`AndroidManifest.xml`), so the
    # name alone counts - which means a moved file is still found, and a renamed one is
    # not, which is the distinction that matters.
    return any(True for _ in ROOT.rglob(pathlib.Path(token).name))


def workflow_secrets():
    """Every repository secret the workflows read, minus the one GitHub provides."""
    names = set()
    for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
        for match in re.finditer(r"secrets\.([A-Z][A-Z0-9_]*)",
                                path.read_text(encoding="utf-8")):
            if match.group(1) != "GITHUB_TOKEN":
                names.add(match.group(1))
    return names


def build_properties():
    """Every `-PMEMORYMAP_...` property the build itself reads."""
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    return set(re.findall(r'findProperty\("(MEMORYMAP_[A-Z0-9_]+)"\)', text))


def documented_names():
    """Every `MEMORYMAP_...` name the release document tells a person to create."""
    text = (ROOT / "docs/RELEASE.md").read_text(encoding="utf-8")
    return set(re.findall(r"MEMORYMAP_[A-Z0-9_]+", text))


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
        for quoted, actual in zip(numbers, expected):
            if quoted is None:
                problems.append(
                    f"{document.name}: {description} is spelled with a word this guard has "
                    f"no number for - add it to WORDS, or write it in digits"
                )
            elif quoted != actual:
                problems.append(
                    f"{document.name}: {description} says {quoted}, this repository has "
                    f"{actual}"
                )
        checked += 1

    # ---------------------------------------------------------------- the paths
    seen = set()
    paths = 0
    for name, token, line in path_claims():
        seen.add(pathlib.Path(token).name)
        if resolves(token):
            paths += 1
            continue
        if pathlib.Path(token).name in NOT_IN_THE_REPOSITORY:
            continue
        problems.append(f"{name}:{line}: points at `{token}`, which is not in this repository")

    for exempt, reason in NOT_IN_THE_REPOSITORY.items():
        if exempt not in seen:
            problems.append(
                f"the exemption for `{exempt}` ({reason}) no longer matches a claim in any "
                f"document"
            )

    # ------------------------------------------------------------- the secret names
    secrets = workflow_secrets()
    properties = build_properties()
    documented = documented_names()

    for name in sorted(secrets - documented):
        problems.append(
            f"a workflow reads the secret {name}, which docs/RELEASE.md never mentions - a "
            f"release would fall back to debug signing and say nothing"
        )
    for name in sorted(documented - secrets - properties):
        problems.append(
            f"docs/RELEASE.md tells a maintainer to create {name}, and nothing reads it"
        )
    if not secrets:
        problems.append(
            "no workflow reads any secret, which cannot be right for a project that "
            "publishes signed builds - this check is looking in the wrong place"
        )

    for problem in problems:
        print(problem)
    if problems:
        print(f"document check: FAIL ({len(problems)} claims that are not true)")
        return 1

    print(f"document check: PASS ({checked} numeric claims, {paths} paths and "
          f"{len(secrets)} secret names agree with the repository)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
