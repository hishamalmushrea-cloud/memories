#!/usr/bin/env python3
"""Apply supabase/schema.sql to a real PostgreSQL and assert what the app needs.

Why this exists
---------------
`schema.sql` had never been executed anywhere. A Supabase migration can read
correctly and still fail on the first statement a real database runs - a policy
referencing a function that does not exist yet, a `with check` written where only
`using` is allowed, a trigger on a column that was renamed. Nothing in the Android
build touches SQL, so the only thing that ever looked at this file was a Kotlin
test reading it as text.

What it does
------------
1. starts a throwaway PostgreSQL (a system installation when one is present, else
   the `pgserver` wheel, which bundles its own),
2. creates the Supabase shapes the migration assumes -
   `supabase/verify/00_supabase_stubs.sql`,
3. applies `supabase/schema.sql` from its own path with `ON_ERROR_STOP`, so a
   broken statement fails the run and is reported with its real line - and with
   the file itself rather than a copy, because a function body must be stored
   exactly as the file wrote it,
4. applies it a second time: a migration that cannot be re-applied is a trap,
5. runs `supabase/verify/10_checks.sql`, which drives the policies, the triggers
   and the two deletion functions as a signed-in user and as an anonymous one,
   printing `ok: ...` for every guarantee it confirms,
6. checks that every table and every function the Kotlin client names exists.

The one statement that cannot always run is `create extension "pgcrypto"`:
Supabase ships it, where it is a no-op, but a PostgreSQL built without contrib
does not have it. When that is the case the line is replaced in a copy, the copy
is checked to differ from the file by nothing else, and the substitution is
reported - never silent. The only function this schema asks of pgcrypto is
`gen_random_uuid()`, which PostgreSQL provides itself from 13 on, and a check
asserts that it works.

Usage
-----
    python3 ci/check-schema.py                 # scratch database, then tear down
    python3 ci/check-schema.py --data-dir DIR  # reuse a scratch cluster
    python3 ci/check-schema.py --keep          # leave the server running

Exit code is 0 only when the schema applies twice and every check passes.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA = REPO / "supabase" / "schema.sql"
STUBS = REPO / "supabase" / "verify" / "00_supabase_stubs.sql"
CHECKS = REPO / "supabase" / "verify" / "10_checks.sql"
REMOTE = REPO / "app" / "src" / "main" / "java" / "com" / "memorymap" / "data" / "remote"
BUILD_DIR = REPO / "app" / "build" / "schema-check"
LOG = BUILD_DIR / "schema.log"

ERROR_LINE = re.compile(r"^(ERROR|FATAL|PANIC):", re.MULTILINE)

# `^` and `$` need MULTILINE: this runs over the whole file, and without it they
# anchor to the ends of the file and nothing is matched.
PGCRYPTO = re.compile(
    r'^[ \t]*create[ \t]+extension[ \t]+if[ \t]+not[ \t]+exists[ \t]+"?pgcrypto"?[ \t]*;[ \t]*$',
    re.IGNORECASE | re.MULTILINE,
)

# Where a system PostgreSQL keeps its binaries when it is not on PATH.
SYSTEM_BIN_GLOBS = [
    "/usr/lib/postgresql/*/bin",
    "/usr/pgsql-*/bin",
    "/opt/homebrew/opt/postgresql*/bin",
    "/usr/local/opt/postgresql*/bin",
    "/usr/local/pgsql/bin",
]


class SqlError(RuntimeError):
    """A SQL statement failed, carrying the server's own output."""


def pgserver_bundle() -> str | None:
    """Where the `pgserver` wheel keeps the PostgreSQL it bundles, if installed."""
    try:
        from pgserver._commands import POSTGRES_BIN_PATH  # noqa: PLC0415

        return str(POSTGRES_BIN_PATH)
    except Exception:  # noqa: BLE001 - it is optional, and any failure means "not there"
        return None


def find_binaries() -> Path | None:
    """A directory holding initdb, pg_ctl and psql, or None.

    A system installation is preferred - it is the one a user would have, and on
    a GitHub runner it has contrib, which means `pgcrypto`. The wheel's bundle is
    the fallback: a real PostgreSQL, just without the extensions.
    """
    from shutil import which

    candidates = [str(Path(which(name)).parent) for name in ("initdb", "pg_ctl", "psql") if which(name)]
    for pattern in SYSTEM_BIN_GLOBS:
        candidates.extend(glob.glob(pattern))
    bundled = pgserver_bundle()
    if bundled:
        candidates.append(bundled)
    seen: list[str] = []
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.append(candidate)
        if all((Path(candidate) / name).exists() for name in ("initdb", "pg_ctl", "psql")):
            return Path(candidate)
    return None


class Server:
    """A throwaway PostgreSQL, driven through the psql that belongs to it."""

    def __init__(self, data_dir: str | None) -> None:
        self.data_dir = data_dir or tempfile.mkdtemp(prefix="memorymap-schema-")
        self.bin_dir: Path | None = None
        self.uri = ""
        self.started = False

    def __enter__(self) -> "Server":
        os.makedirs(self.data_dir, exist_ok=True)
        binaries = find_binaries()
        if binaries is None:
            raise SystemExit(
                "no PostgreSQL found. Install one with\n"
                "  python3 -m pip install --break-system-packages pgserver\n"
                "or install the postgresql server from your package manager."
            )
        self.bin_dir = binaries
        socket_dir = Path(self.data_dir) / "socket"
        # initdb insists on an empty directory, so the socket directory - which
        # lives inside it - is created only once the cluster exists.
        if not (Path(self.data_dir) / "PG_VERSION").exists():
            self.run_or_explain(
                [str(binaries / "initdb"), "-D", self.data_dir, "-U", "postgres", "-A", "trust", "--no-sync"],
                "initdb could not create the scratch cluster",
            )
        socket_dir.mkdir(parents=True, exist_ok=True)
        # One server per scratch directory: a second run against the same
        # directory has nothing to gain and would fight over the socket.
        status = subprocess.run(
            [str(binaries / "pg_ctl"), "-D", self.data_dir, "status"],
            capture_output=True,
            text=True,
            check=False,
        )
        if status.returncode != 0:
            self.run_or_explain(
                [
                    str(binaries / "pg_ctl"),
                    "-D", self.data_dir,
                    "-o", f"-k {socket_dir} -c listen_addresses='' -c fsync=off",
                    "-w", "-l", str(Path(self.data_dir) / "server.log"),
                    "start",
                ],
                "the scratch server did not start",
            )
            self.started = True
        self.uri = f"postgresql://postgres@/postgres?host={socket_dir}"
        version = subprocess.run(
            [str(binaries / "postgres"), "--version"], capture_output=True, text=True, check=False
        ).stdout.strip()
        print(f"postgres: {version or binaries}")
        print(f"          {self.scalar('select version();')}")
        return self

    @staticmethod
    def run_or_explain(command: list[str], what: str) -> None:
        """Run a helper and print what it said, rather than a traceback."""
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            output = ((result.stdout or "") + (result.stderr or "")).strip()
            raise SystemExit(f"{what} (exit {result.returncode}):\n{output}")

    # -- running SQL ---------------------------------------------------------

    def psql(self, arguments: list[str], stdin: str | None = None) -> str:
        """Run psql, capturing both streams, raising on the first error."""
        assert self.bin_dir is not None
        result = subprocess.run(
            [str(self.bin_dir / "psql"), self.uri, "-v", "ON_ERROR_STOP=1", "-q", *arguments],
            input=stdin,
            capture_output=True,
            text=True,
            check=False,
        )
        output = (result.stdout or "") + (result.stderr or "")
        if result.returncode != 0 or ERROR_LINE.search(output):
            raise SqlError(output)
        return result.stdout or ""

    def run(self, sql: str) -> str:
        return self.psql([], stdin=sql)

    def run_file(self, path: Path) -> str:
        return self.psql(["-f", str(path)])

    def scalar(self, sql: str) -> str:
        assert self.bin_dir is not None
        result = subprocess.run(
            [str(self.bin_dir / "psql"), self.uri, "-tAX", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise SqlError((result.stdout or "") + (result.stderr or ""))
        return result.stdout.strip()

    def has_extension(self, name: str) -> bool:
        return self.scalar(f"select count(*) from pg_available_extensions where name = '{name}';") == "1"

    def fresh_database(self) -> None:
        self.run(
            "drop schema if exists public cascade; create schema public;"
            "drop schema if exists auth cascade;"
            "drop schema if exists storage cascade;"
        )

    def __exit__(self, *_exc: object) -> None:
        if not self.started or self.bin_dir is None or os.environ.get("MEMORYMAP_KEEP_PG"):
            return
        subprocess.run(
            [str(self.bin_dir / "pg_ctl"), "-D", self.data_dir, "-m", "immediate", "stop"],
            capture_output=True,
            text=True,
            check=False,
        )


def report_failure(label: str, output: str) -> None:
    """Print a failure with the line of the real file that caused it."""
    print(f"\nFAIL: {label}", file=sys.stderr)
    lines = output.splitlines()
    for index, line in enumerate(lines):
        if line.startswith("ERROR:") or line.startswith("FATAL:"):
            print(f"  {line.strip()}", file=sys.stderr)
            for follow in lines[index + 1 : index + 9]:
                if follow.startswith(("LINE ", "CONTEXT:", "HINT:", "DETAIL:")):
                    print(f"  {follow.strip()}", file=sys.stderr)
    for line in lines:
        match = re.search(r"psql:(\S+\.sql):(\d+):", line)
        if not match:
            continue
        path, number = Path(match.group(1)), int(match.group(2))
        if not path.exists():
            continue
        body = path.read_text(encoding="utf-8").splitlines()
        if not 1 <= number <= len(body):
            continue
        print(f"\n  causing statement -> {path.name}:{number}: {body[number - 1].strip()}", file=sys.stderr)
        # A check that fails does so by raising, and plpgsql reports the line of
        # the statement it was running - the end of the `do $$ ... $$`. The raise
        # a few lines above is the guarantee that broke, so name that instead.
        for above in range(number - 1, max(number - 60, -1), -1):
            if "raise exception" in body[above]:
                print(f"  guarantee        -> {path.name}:{above + 1}: {body[above].strip()}", file=sys.stderr)
                break
        context = [entry for entry in body[max(number - 5, 0) : number + 1] if entry.strip()]
        print("  " + "\n  ".join(entry.strip() for entry in context), file=sys.stderr)
    print("\n  --- server output (tail) ---", file=sys.stderr)
    for line in [entry for entry in lines if entry.strip()][-15:]:
        print(f"  {line}", file=sys.stderr)


def neutralise_pgcrypto(schema_body: str) -> Path | None:
    """Copy the schema with the pgcrypto line commented out, or None if absent.

    Only used where the extension is genuinely unavailable. The copy is verified
    to differ from the file by exactly that line, so nothing else can ride along.
    """
    patched, count = PGCRYPTO.subn(
        '-- [schema-check] `create extension "pgcrypto"` skipped: this PostgreSQL has no pgcrypto.\n'
        "-- [schema-check] Supabase ships it, and there the statement is a no-op.",
        schema_body,
    )
    if count == 0:
        return None
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    path = BUILD_DIR / "schema_without_pgcrypto.sql"
    path.write_text(patched, encoding="utf-8")

    # The copy with the marker comments removed must be the file with exactly the
    # pgcrypto line taken out - and nothing else.
    expected = [line for line in schema_body.splitlines() if not PGCRYPTO.match(line)]
    actual = [line for line in patched.splitlines() if not line.lstrip().startswith("-- [schema-check]")]
    if actual != expected:
        raise SystemExit("refusing to apply the copy: it differs from the file by more than the pgcrypto line")
    return path


def client_names() -> tuple[list[str], list[str]]:
    """The tables and the RPCs the Kotlin client actually names.

    Read out of the code that talks to Postgrest rather than out of the SQL, so a
    rename on either side - a table that moved, a function the app calls by a name
    the schema does not define - shows up here. `SyncTable.name` is deliberately
    not used for this: it is the word the user sees in a message, and
    `attachments` is one of those words while the table is `media`.
    """
    api = (REMOTE / "PostgrestSyncApi.kt").read_text(encoding="utf-8")
    tables = sorted(set(re.findall(r'const val TABLE_\w+ = "([A-Za-z0-9_]+)"', api)))

    account = (REMOTE / "AccountApi.kt").read_text(encoding="utf-8")
    functions = sorted(set(re.findall(r'const val DELETE_\w+ = "([A-Za-z0-9_]+)"', account)))
    if not tables or not functions:
        raise SystemExit("no table or function names were read out of the client: the constants moved")
    return tables, functions


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data-dir", help="scratch data directory for the server")
    parser.add_argument("--keep", action="store_true", help="leave the server running")
    args = parser.parse_args()

    schema = SCHEMA
    skipped: list[str] = []
    with Server(args.data_dir) as server:
        if not server.has_extension("pgcrypto"):
            copy = neutralise_pgcrypto(SCHEMA.read_text(encoding="utf-8"))
            if copy is not None:
                schema = copy
                skipped.append('create extension "pgcrypto" (not installed in this PostgreSQL)')
            print("note: this PostgreSQL has no pgcrypto, so that one line is skipped.")
            print("      Supabase ships pgcrypto, where the statement is a no-op, and the only")
            print("      function the schema uses from it, gen_random_uuid(), is part of")
            print("      PostgreSQL itself from 13 on - a check below uses it.")

        server.fresh_database()

        try:
            print("\n-- supabase/verify/00_supabase_stubs.sql")
            print(server.run_file(STUBS).strip())
            print("-- supabase/schema.sql (first application)")
            print(server.run_file(schema).strip())
        except SqlError as error:
            report_failure("the schema did not apply", str(error))
            return 1

        try:
            print("-- supabase/schema.sql (second application, must be re-runnable)")
            print(server.run_file(schema).strip())
        except SqlError as error:
            report_failure("the schema is not re-runnable", str(error))
            return 1

        try:
            print("-- supabase/verify/10_checks.sql")
            output = server.run_file(CHECKS)
        except SqlError as error:
            report_failure("a check failed", str(error))
            return 1

        tables, functions = client_names()
        missing = [
            name
            for name in tables
            if server.scalar(
                "select count(*) from information_schema.tables "
                f"where table_schema = 'public' and table_name = '{name}';"
            )
            != "1"
        ]
        if missing:
            print(f"\nFAIL: tables the client names that do not exist: {', '.join(missing)}", file=sys.stderr)
            return 1
        absent = [
            name
            for name in functions
            if server.scalar(
                "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                f"where n.nspname = 'public' and p.proname = '{name}';"
            )
            != "1"
        ]
        if absent:
            print(f"\nFAIL: functions the client calls that do not exist: {', '.join(absent)}", file=sys.stderr)
            return 1

        if args.keep:
            print(f"\nserver left running at {server.uri}")

    for line in output.splitlines():
        if line.startswith("ok:"):
            print(line)
    print(f"ok: every table the client names exists ({len(tables)}: {', '.join(tables)})")
    print(f"ok: every function the client calls exists ({len(functions)}: {', '.join(functions)})")

    guarantees = sum(1 for line in output.splitlines() if line.startswith("ok:")) + 2
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    LOG.write_text(output, encoding="utf-8")

    print(f"\nschema check: PASS ({guarantees} guarantees, applied twice to a real PostgreSQL)")
    if skipped:
        print("              skipped: " + "; ".join(skipped))
    print(f"full output: {LOG.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
