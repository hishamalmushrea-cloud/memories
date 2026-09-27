#!/usr/bin/env python3
"""Fail when a signing key or a signing password has reached the repository.

A leaked signing key cannot be rotated with a commit. Android only accepts an
update signed by the certificate the installed copy already trusts, so a key in
the history does not cost one release - it costs the app's identity: the package
has to be renamed, and every install has to be replaced by hand. That makes this
the one mistake in the project that a later commit cannot undo, which is why it
is checked here rather than trusted to .gitignore.

Two questions are asked, and they are different questions:

  A) Is a key-shaped file tracked? .gitignore only stops files that were never
     added; a file added before the rule, or added with `git add -f`, is tracked
     forever and is invisible to .gitignore. `git ls-files` is the answer that
     does not care what .gitignore says.
  B) Does any tracked file contain a key or a password? A keystore pasted into a
     note, a base64 blob in a test fixture, a fingerprint file that grew a
     private key - none of those have a suspicious name.

The base64 form is deliberately included: `base64 release.jks > key.b64` leaves
the same private key in the tree, in an alphabet that greps for "PRIVATE KEY"
will not match. It is the shape a person would reach for.

Run it from anywhere in the repository; it reads the Git index, not the working
tree, so untracked scratch files under /tmp or an ignored local keystore do not
produce noise. Exit status 0 means nothing was found.
"""

from __future__ import annotations

import re
import subprocess
import sys

# Names that say "this is a key or a password" on their own.
KEY_FILE = re.compile(r"\.(jks|keystore|p12|pfx|b64|pem|der)$", re.IGNORECASE)
PW_FILE = re.compile(r"(password|passwd|secret)", re.IGNORECASE)
KEY_WORD = re.compile(r"(keystore|jks|release.?key|signing)", re.IGNORECASE)

# A PKCS12 or JKS store that was pasted into a text file keeps its bytes or its
# base64. Both are searched for, because a store is not a text file and cannot be
# recognised by looking at the first line.
DER_HEAD = b"\x30\x82"
B64_HEAD = "MII"

# Text that is a private key wherever it appears. The markers are assembled from
# pieces so that this file does not contain them: a checker that reads every
# tracked file reads itself, and a checker that fails on itself is a checker
# everybody disables.
_HEAD = b"-----BEGIN "
_TAIL = b"-----"
PRIVATE_KEY = tuple(
    _HEAD + name + _TAIL
    for name in (b"PRIVATE KEY", b"RSA PRIVATE KEY", b"EC PRIVATE KEY")
)


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], capture_output=True, check=True
    ).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def main() -> int:
    problems = 0
    files = tracked_files()

    def fail(message: str) -> None:
        nonlocal problems
        problems += 1
        print(f"FAIL: {message}")

    for path in files:
        name = path.rsplit("/", 1)[-1]

        # A) the file's own name. A fingerprint file is allowed to say "keystore"
        #    or to be named release-fingerprint; the certificate in it is public.
        if KEY_FILE.search(path) and "fingerprint" not in name:
            fail(f"{path} is tracked and its extension says it is a signing key")
        if KEY_WORD.search(name) and PW_FILE.search(name):
            fail(f"{path} is tracked and its name says it holds a signing password")

        # B) the file's contents.
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError:
            continue

        for marker in PRIVATE_KEY:
            if marker in data:
                fail(f"{path} contains a private key ({marker.decode().strip()})")

        # A raw store starts with the DER SEQUENCE tag and the length bytes that
        # follow it. Checked only at the very start, so a PNG or a JAR that
        # happens to contain those bytes further in is not accused.
        if data[:2] == DER_HEAD and len(data) > 64:
            fail(f"{path} begins with a DER sequence; it may be a raw keystore")

        # The base64 form: 4 characters per 3 bytes, so a store of a few thousand
        # bytes arrives as one long line starting with MII (the DER header, encoded)
        # and ending with '=' padding. A short line is not a keystore, and the file
        # has to be plain text, so anything with NUL bytes is skipped.
        if b"\0" not in data and 2000 < len(data) < 200_000:
            text = data.decode("utf-8", errors="ignore")
            for line in text.splitlines():
                stripped = line.strip()
                if len(stripped) < 1000 or not stripped.startswith(B64_HEAD):
                    continue
                if re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", stripped):
                    fail(
                        f"{path} contains a {len(stripped)}-character base64 blob that "
                        "decodes like a keystore; it must live in a CI secret"
                    )
                    break

    print(
        f"keystore leak check: {len(files)} tracked files, no signing key or "
        f"password found" if problems == 0 else
        f"keystore leak check: {len(files)} tracked files, {problems} problem(s)"
    )
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
