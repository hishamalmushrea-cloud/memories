#!/usr/bin/env python3
"""Fail the build when a failure after a deliberate action would say nothing.

The app had twenty-six failure paths under `ui/` whose only effect was a line in
the log - and a line in the log is, to the person holding the phone, nothing at
all. That was fixed screen by screen: the memory editor, the memories list, the
memory detail, the day screen, the event editor, and the people and places
screens all report a failure now. This guard is what keeps the twenty-seventh
from being written.

The rule it enforces is deliberately about the *function*, not the block. A
failure block that logs is fine when the function it sits in reports something
somewhere - `importFromUri` logs the exception, returns null, and the caller's
next statement sets the message. What is not fine is a function where a failure
can only ever reach `MmLog`.

The reads are the exception, and they are listed here one by one with the reason
each is allowed to stay quiet. A read that fails shows an empty screen, which is
true and not misleading, whereas an action that fails and says nothing is a
button that appears dead. The list is checked in both directions: an entry that
no longer describes a real log-only path is itself a failure, so the exemption
cannot quietly outlive its reason.

There is no allowlist keyed by line number, because line numbers move. Entries
are keyed by file and by the log message, which is what a reviewer reads anyway.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/com/memorymap/ui"

# What a function can do to make a failure visible. `message.value` is the day
# screen's channel; `errorRes` is everyone else's. The message has to be a
# resource: clearing the field (`errorRes = null`) is not reporting, and a
# function that only clears its message is exactly the bug this looks for.
REPORTING = (
    re.compile(r"errorRes\s*=\s*R\.string\."),
    re.compile(r"message\.value\s*=\s*R\.string\."),
    re.compile(r"showMessage"),
)

# Every log-only failure that is deliberate, with the reason it is allowed to
# stay log-only. Adding a line here is the decision; writing the reason is what
# makes it reviewable.
ALLOWED = {

    ("common/MediaPlayback.kt", "Unable to pause playback"):
        "playback: the sound either stops or it does not, and a banner over a "
        "video would be worse than the silence",
    ("common/MediaPlayback.kt", "Unable to seek"):
        "playback: a seek that fails leaves the player where it was, on screen",
    ("diary/DayViewModel.kt", "Unable to load the day"):
        "read: a day that will not load shows an empty day",
    ("diary/DiaryPeriodViewModel.kt", "Unable to load the diary period"):
        "read: a week, month or year that will not load shows an empty one",
    ("diary/EntryEditorViewModel.kt", "Unable to load the event"):
        "read: the editor opens with an empty form, which is what a new event "
        "looks like",
    ("map/MapScreen.kt", "Unable to read the location"):
        "read: the location picker opens centred on nothing rather than on the "
        "last known position",
    ("map/MapViewModel.kt", "Unable to load the map markers"):
        "read: a map with no markers is a map",
    ("nearby/NearbyScreen.kt", "Unable to read the location"):
        "read: with no position there is nothing near to list",
    ("profile/ProfileViewModel.kt", "Unable to compute the life statistics"):
        "read: the counts under the profile show as empty",
}

FUNCTION_RE = re.compile(r"^[ \t]*(?:private |internal |public |protected )?"
                         r"(?:suspend )?(?:override )?fun[ \t]+(?:<[^>]*>[ \t]+)?(\w+)")


def functions(text: str):
    """Every function in the file as (name, start_line, body)."""
    lines = text.splitlines()
    starts = []
    for index, line in enumerate(lines):
        match = FUNCTION_RE.match(line)
        if match:
            indent = len(line) - len(line.lstrip())
            starts.append((index, indent, match.group(1)))
    for position, (index, indent, name) in enumerate(starts):
        end = len(lines)
        for next_index, next_indent, _ in starts[position + 1:]:
            # A function ends at the next declaration at the same or a shallower
            # indent; a nested lambda is indented further and stays inside.
            if next_indent <= indent:
                end = next_index
                break
        yield name, index, "\n".join(lines[index:end])


def blocks(text: str):
    """Every `onFailure { ... }` in the file as (line number, block body)."""
    for match in re.finditer(r"onFailure\s*\{", text):
        index, depth = match.end(), 1
        while depth and index < len(text):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
            index += 1
        line = text[:match.start()].count("\n") + 1
        yield line, text[match.end():index - 1]


def main() -> int:
    if not UI.is_dir():
        print(f"the UI sources are not where this guard expects them: {UI}")
        return 1

    seen = set()
    silent = []
    total = 0

    for path in sorted(UI.rglob("*.kt")):
        relative = path.relative_to(UI).as_posix()
        text = path.read_text(encoding="utf-8")
        bodies = list(functions(text))
        for line, block in blocks(text):
            log = re.search(r'MmLog\.\w+\(\s*"([^"]+)"', block)
            if not log:
                continue
            total += 1
            message = log.group(1)
            # The function the block sits in, which is the unit that has to
            # report: the message may be set after the block, as importFromUri
            # does when the import comes back null.
            enclosing = None
            for name, start, body in bodies:
                if start + 1 <= line:
                    enclosing = body
                else:
                    break
            if enclosing and any(pattern.search(enclosing) for pattern in REPORTING):
                continue
            silent.append((relative, line, message))
            seen.add((relative, message))

    stale = sorted(entry for entry in ALLOWED if entry not in seen)
    unexplained = [
        (relative, line, message)
        for relative, line, message in silent
        if (relative, message) not in ALLOWED
    ]

    for relative, line, message in unexplained:
        print(f"{relative}:{line}: a failure after an action says nothing but "
              f"\"{message}\"")
    for relative, message in stale:
        print(f"{relative}: the exemption for \"{message}\" no longer describes "
              f"a log-only failure")

    if unexplained or stale:
        print(f"{len(silent)} log-only failure path(s), "
              f"{len(ALLOWED)} exempted, {len(stale)} stale, "
              f"{len(unexplained)} unexplained")
        return 1

    print(f"OK - {total} logged failure path(s), {len(silent)} deliberately "
          f"log-only and each one listed with its reason")
    return 0


if __name__ == "__main__":
    sys.exit(main())
