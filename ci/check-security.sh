#!/usr/bin/env bash
# Fails the build when a security or privacy guarantee this project documents
# stops holding in the source.
#
# These are the properties that are easy to break by accident and expensive to
# notice later: a secret key committed to the client, a log call that bypasses
# MmLog, an OS backup that uploads the diary, a shrinker rule that silently
# stops stripping logs. Each check prints the file and line it objects to, so a
# failure says what to fix rather than only that something is wrong.
set -uo pipefail

SRC="app/src/main/java"
MANIFEST="app/src/main/AndroidManifest.xml"
PROGUARD="app/proguard-rules.pro"
BUILD="app/build.gradle.kts"

problems=0

fail() {
    echo "FAIL: $1"
    problems=$((problems + 1))
}

# 1. The service_role key must never reach a client build; everything inside an
#    APK is public. Comments explaining the rule are fine, so comment lines are
#    dropped before matching.
while IFS= read -r line; do
    [ -n "$line" ] && fail "service_role outside a comment: $line"
done < <(grep -rn "service_role" "$SRC" 2>/dev/null | grep -vE ':[0-9]+: *(\*|//|/\*)' || true)

# 2. Logcat goes through MmLog and nowhere else, because MmLog is the one place
#    release builds strip. A direct Log call would survive shrinking.
while IFS= read -r line; do
    [ -n "$line" ] && fail "direct android.util.Log call outside MmLog: $line"
done < <(grep -rnE "\bLog\.[dviwe]\(" "$SRC" 2>/dev/null | grep -v "util/MmLog.kt" || true)

# 3. Location is read on demand. There is no background tracking.
if grep -q "ACCESS_BACKGROUND_LOCATION" "$MANIFEST"; then
    fail "ACCESS_BACKGROUND_LOCATION is declared, but location is on demand only"
fi

# 4. The diary is never handed to the operating system's cloud backup.
if grep -q 'android:allowBackup="true"' "$MANIFEST"; then
    fail 'android:allowBackup="true" would upload the diary to a third-party backup'
fi

# 5. HTTPS only, so the session token never crosses the network in the clear.
if grep -q 'android:usesCleartextTraffic="true"' "$MANIFEST"; then
    fail 'android:usesCleartextTraffic="true" permits plain HTTP'
fi

# 6. Without R8 nothing is stripped at all, which would make check 7 moot.
if ! grep -q "isMinifyEnabled = true" "$BUILD"; then
    fail "release minification is off, so the log-stripping rules never run"
fi

# 7. MmLog is a Kotlin object, so its methods are instance methods on the
#    singleton. A "static" signature matches nothing and strips nothing.
if ! grep -q "assumenosideeffects class com.memorymap.util.MmLog" "$PROGUARD"; then
    fail "the MmLog log-stripping rule is missing from $PROGUARD"
elif grep -A4 "assumenosideeffects class com.memorymap.util.MmLog" "$PROGUARD" |
    grep -qE "static +[a-z*]+ +[dv]\("; then
    fail "MmLog d()/v() are declared static; they are instance methods, so nothing is stripped"
fi

# 8. Every table the schema enables RLS on must have at least one policy, or it
#    is locked to the owner by accident and silently unusable.
python3 - <<'PY' || problems=$((problems + 1))
import re, sys

sql = open("supabase/schema.sql", encoding="utf-8").read()
guarded = set(re.findall(r"alter table public\.(\w+)\s+enable row level security", sql))
policies = set(re.findall(r'create policy "[^"]+" on public\.(\w+)', sql))
missing = sorted(guarded - policies)
if missing:
    print("FAIL: RLS enabled with no policy: " + ", ".join(missing))
    sys.exit(1)
print(f"RLS: {len(guarded)} tables guarded, {len(policies)} with policies")
PY

if [ "$problems" -eq 0 ]; then
    echo "security check: PASS"
    exit 0
fi
echo "security check: FAIL ($problems problem(s))"
exit 1
