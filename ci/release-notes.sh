#!/usr/bin/env bash
# Prints the changelog section for one release tag.
#
#   bash ci/release-notes.sh v0.1.0 [CHANGELOG.md]
#
# The release workflow uses this to build the notes of a GitHub release, and
# `ci/check-release-notes.py` runs it on every push so that a tag cannot be the
# first time it is executed.
#
# The version is matched as a whole bracketed heading, not as a substring. The
# first version of this was an `awk` that asked whether the line contained the
# version anywhere, which means a tag of `v0.1.0` would also have matched a
# section headed `## [10.1.0]` and published the wrong notes under the wrong tag.
#
# Exit status: 0 with the section on stdout, or 1 with nothing on stdout when the
# changelog has no section for that tag. The caller decides what to do then; the
# workflow falls back to the whole changelog and says so in the run.
set -uo pipefail

TAG="${1:-}"
CHANGELOG="${2:-CHANGELOG.md}"

if [ -z "$TAG" ]; then
  echo "usage: $0 <tag> [changelog]" >&2
  exit 2
fi
if [ ! -f "$CHANGELOG" ]; then
  echo "no changelog at $CHANGELOG" >&2
  exit 1
fi

VERSION="${TAG#v}"

awk -v want="$VERSION" '
  # `## [1.0.0] - 2026-01-01` and nothing else on the line that changes the answer.
  function is_ours(line,   rest) {
    if (line !~ /^## \[/) return 0
    rest = substr(line, 5)
    # The heading ends at the closing bracket; the version must be all of it.
    return index(rest, want "]") == 1
  }
  # Link definitions live at the bottom of a Keep-a-Changelog file and are not
  # part of any section; without this the notes end with `[0.1.0]: https://...`.
  printing && /^\[[^]]+\]:/ { exit }
  /^## \[/ {
    if (is_ours($0)) { printing = 1; print; next }
    if (printing) exit
    printing = 0
    next
  }
  printing { print }
' "$CHANGELOG" > /tmp/release-notes.out

SECTION="$(cat /tmp/release-notes.out)"
# `awk` exits 0 whether or not it printed anything, so the verdict is the output.
if [ -z "$SECTION" ]; then
  echo "no changelog section for $TAG in $CHANGELOG" >&2
  exit 1
fi

# One trailing blank line, so the notes do not end with a gap.
printf '%s\n' "$SECTION"
