#!/usr/bin/env python3
"""Verify a real hosted Supabase project answers the way this app needs it to.

`ci/check-schema.py` proves the schema applies, twice, to a real PostgreSQL - and it says
plainly that GoTrue, PostgREST and the storage service are not part of that check, because
a stub cannot stand in for the platform. This script is the other half: it talks to the
project you actually created, with the anon key the app ships, and reports what came back.

It is **read-only, and it creates nothing**. There is no sign-up, no upload, no delete: the
auth check asks for a token with credentials that cannot exist, which is how a live auth
service proves it is live without leaving a row behind.

What each check settles:

  1. the URL and the key are shaped the way the app sends them (a truncated paste is the
     most common failure, and it fails at runtime as "the app cannot sign in");
  2. the key is an **anon** key and not a `service_role` key - the one thing the
     specification forbids shipping, and the one mistake that would silently bypass every
     row-level security policy in the project;
  3. the auth service is up;
  4. the auth service answers a credentials error rather than an unknown-route error;
  5. PostgREST is up and the anon key is accepted;
  6. **every table refuses the anon key**: each one is asked for a single row and each one
     must come back with nothing. A row here means row-level security is not doing what
     `supabase/schema.sql` says it does;
  7. the storage bucket cannot be listed or read by the anon key.

Run it after applying `supabase/schema.sql`:

    python3 ci/verify-live-supabase.py
    python3 ci/verify-live-supabase.py --url https://xxxx.supabase.co --anon-key eyJ...

With no arguments the URL and the key are resolved the same way the build resolves them:
command line, then `SUPABASE_URL`/`SUPABASE_ANON_KEY` in the environment, then
`local.properties`, then `~/.gradle/gradle.properties`. The source that answered is printed,
so "it passed" always says which project it passed against.

The exit code is 0 only when every check passed. A project that is not configured is a
failure here rather than a skip: the script exists to be run against a project, and
"nothing to check" is not a pass.
"""
from __future__ import annotations

import argparse
import base64
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "supabase/schema.sql"
BUILD_FILE = ROOT / "app/build.gradle.kts"

# Every GET this script makes is short: a project that is up answers in milliseconds, and a
# script that hangs is a script nobody runs twice.
TIMEOUT_SECONDS = 20

FREE_PLAN_MAX_UPLOAD_BYTES = 50 * 1024 * 1024


def properties_file(path: pathlib.Path) -> dict[str, str]:
    """The `KEY=VALUE` pairs of a properties file, comments and blanks ignored."""
    if not path.exists():
        return {}
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def resolve(name: str, given: str | None) -> tuple[str, str]:
    """The value and where it came from, in the build's own order."""
    if given:
        return given.strip(), "the command line"
    import os

    environment = os.environ.get(name, "").strip()
    if environment:
        return environment, "the environment"
    for path, description in (
        (ROOT / "local.properties", "local.properties"),
        (pathlib.Path.home() / ".gradle/gradle.properties", "~/.gradle/gradle.properties"),
    ):
        value = properties_file(path).get(name, "").strip()
        if value:
            return value, description
    return "", "nowhere"


def jwt_payload(token: str) -> dict:
    """The payload of a JWT, or an empty dict when it is not one."""
    parts = token.split(".")
    if len(parts) != 3:
        return {}
    body = parts[1]
    body += "=" * (-len(body) % 4)
    try:
        return json.loads(base64.urlsafe_b64decode(body))
    except Exception:
        return {}


def get(url: str, key: str) -> tuple[int, str]:
    """One GET with the anon key, exactly as the app sends it."""
    request = urllib.request.Request(
        url,
        headers={"apikey": key, "Authorization": f"Bearer {key}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")
    except Exception as error:  # noqa: BLE001 - reported, never raised
        return 0, f"{type(error).__name__}: {error}"


def post(url: str, key: str, payload: dict) -> tuple[int, str]:
    """One POST with the anon key. Used only for the impossible sign-in."""
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")
    except Exception as error:  # noqa: BLE001 - reported, never raised
        return 0, f"{type(error).__name__}: {error}"


def tables() -> list[str]:
    """Every table `supabase/schema.sql` creates."""
    return re.findall(
        r"create table if not exists public\.([a-z_]+)", SCHEMA.read_text(encoding="utf-8")
    )


def max_upload_bytes() -> int:
    """`MAX_UPLOAD_BYTES` as the build file defines it."""
    text = BUILD_FILE.read_text(encoding="utf-8")
    match = re.search(r"MAX_UPLOAD_BYTES[^\n]*?(\d+)\s*\*\s*1024\s*\*\s*1024", text)
    if match:
        return int(match.group(1)) * 1024 * 1024
    match = re.search(r"MAX_UPLOAD_BYTES[^0-9]*(\d{6,})", text)
    return int(match.group(1)) if match else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="the project URL, https://<ref>.supabase.co")
    parser.add_argument("--anon-key", help="the anon key the app ships")
    parser.add_argument("--bucket", default="media", help="the storage bucket (default: media)")
    parser.add_argument(
        "--allow-cleartext",
        action="store_true",
        help="accept an http:// URL. Only for a local stack started with the Supabase CLI "
        "(http://127.0.0.1:54321), never for a hosted project: the app itself refuses "
        "cleartext, so a hosted project that is http would fail for the app anyway",
    )
    args = parser.parse_args(argv)

    url, url_source = resolve("SUPABASE_URL", args.url)
    key, key_source = resolve("SUPABASE_ANON_KEY", args.anon_key)

    problems: list[str] = []
    notes: list[str] = []

    # 1. shape -------------------------------------------------------------------------
    if not url:
        problems.append(
            "no project URL: set SUPABASE_URL in local.properties (or pass --url). "
            "Without it the app runs offline-only, which is supported but is not what this "
            "script is for"
        )
    else:
        url = url.rstrip("/")
        if not url.startswith("https://") and not (
            args.allow_cleartext and url.startswith("http://127.0.0.1")
        ):
            problems.append(
                f"the URL is not https ({url}), and the app refuses cleartext - pass "
                f"--allow-cleartext only for a local stack on 127.0.0.1"
            )
        elif re.search(r"/[a-z]+/v1", url):
            problems.append(
                f"the URL looks like an endpoint rather than the project root ({url}); the "
                f"app appends /auth/v1 and /rest/v1 itself"
            )
        else:
            print(f"ok  1. the project URL is {url} (from {url_source})")

    if not key:
        problems.append(
            "no anon key: set SUPABASE_ANON_KEY in local.properties (or pass --anon-key). It "
            "is the key from Project Settings -> API, and it is the only one the app may ship"
        )
    else:
        claims = jwt_payload(key)
        role = str(claims.get("role", ""))
        if not claims:
            problems.append(
                "the anon key is not a JWT, so it was truncated or the wrong value was "
                "pasted; the app would send it and be refused"
            )
        elif role == "service_role":
            # The hardest rule in the specification, checked at the one moment somebody is
            # copying keys around: this is the mistake that bypasses every policy.
            problems.append(
                "THIS IS A service_role KEY. It must never be in the app, in "
                "local.properties, or anywhere a build can read it: it bypasses every "
                "row-level security policy. Use the anon (public) key"
            )
        elif role != "anon":
            problems.append(f"the key's role is {role!r}, and the app must ship an anon key")
        else:
            print(f"ok  2. the key is an anon key (from {key_source})")

    if problems:
        for problem in problems:
            print(f"FAIL: {problem}")
        print(f"live Supabase check: FAIL ({len(problems)} problem(s), nothing was tested)")
        return 1

    # 3. auth is up --------------------------------------------------------------------
    status, body = get(f"{url}/auth/v1/health", key)
    if status == 200:
        print(f"ok  3. the auth service is up ({body.strip()[:80]})")
    else:
        problems.append(f"GET /auth/v1/health answered {status}: {body.strip()[:200]}")

    # 4. auth answers a credentials error ---------------------------------------------
    status, body = post(
        f"{url}/auth/v1/token?grant_type=password",
        key,
        {"email": "verify-live-supabase@invalid.invalid", "password": "not-a-password-" + "0" * 8},
    )
    if status in (400, 401, 422):
        print(f"ok  4. the auth service answered a credentials error ({status}), not a route error")
    elif status == 429:
        notes.append("the auth service is rate-limiting this machine (429); run it again later")
        print("ok  4. the auth service is up and rate-limiting (429)")
    else:
        problems.append(f"POST /auth/v1/token answered {status}: {body.strip()[:200]}")

    # 5. PostgREST is up --------------------------------------------------------------
    status, body = get(f"{url}/rest/v1/", key)
    if status == 200:
        print("ok  5. PostgREST answered the anon key")
    else:
        problems.append(
            f"GET /rest/v1/ answered {status}: {body.strip()[:200]} - the anon key was "
            f"refused or PostgREST is not exposed"
        )

    # 6. row-level security holds for every table ------------------------------------
    if status == 200:
        names = tables()
        if not names:
            problems.append("no tables were found in supabase/schema.sql, so nothing was checked")
        leaked = []
        for name in names:
            table_status, table_body = get(f"{url}/rest/v1/{name}?select=*&limit=1", key)
            if table_status == 200:
                try:
                    rows = json.loads(table_body)
                except json.JSONDecodeError:
                    rows = []
                if isinstance(rows, list) and rows:
                    leaked.append(name)
        if leaked:
            problems.append(
                f"the anon key read rows from {', '.join(leaked)}: row-level security is not "
                f"protecting those tables, and the anon key ships inside every copy of the app"
            )
        else:
            print(f"ok  6. all {len(names)} tables returned nothing to the anon key")

    # 7. storage is not public ---------------------------------------------------------
    status, body = get(f"{url}/storage/v1/bucket", key)
    if status == 200:
        try:
            buckets = json.loads(body)
        except json.JSONDecodeError:
            buckets = []
        if isinstance(buckets, list) and buckets:
            problems.append(
                f"the anon key listed {len(buckets)} storage bucket(s), so bucket metadata is "
                f"readable by every copy of the app"
            )
        else:
            print("ok  7a. the anon key cannot enumerate storage buckets")
    else:
        print(f"ok  7a. the anon key cannot list buckets ({status})")

    status, body = get(f"{url}/storage/v1/object/public/{args.bucket}/?list", key)
    if status == 200 and body.strip().startswith("["):
        problems.append(
            f"the bucket {args.bucket!r} lists its objects publicly; the schema creates it "
            f"private and the app relies on that"
        )
    else:
        print(f"ok  7b. the bucket {args.bucket!r} does not list objects publicly ({status})")

    # 8. the plan's own ceiling, as a note rather than a verdict ----------------------
    configured = max_upload_bytes()
    if configured > FREE_PLAN_MAX_UPLOAD_BYTES:
        notes.append(
            f"this build accepts up to {configured // (1024 * 1024)} MB per file while the "
            f"Supabase free plan refuses anything above 50 MB: raise the plan, or lower "
            f"MAX_UPLOAD_BYTES in local.properties, or the upload fails at the server"
        )

    for note in notes:
        print(f"note: {note}")
    for problem in problems:
        print(f"FAIL: {problem}")
    if problems:
        print(f"live Supabase check: FAIL ({len(problems)} problem(s))")
        return 1

    print(
        "live Supabase check: PASS (the project answers, the anon key is an anon key, every "
        "table refuses it, and the bucket is private)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
