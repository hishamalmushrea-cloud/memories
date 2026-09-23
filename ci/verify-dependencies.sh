#!/usr/bin/env bash
# Fails the build early, with one line per missing coordinate, when a version in
# gradle/libs.versions.toml does not exist on the repositories.
#
# Gradle would report the same problem eventually, but only after resolving the
# whole graph; this makes a wrong version a 30 second failure instead of a
# several minute one.
set -uo pipefail

CATALOG="${1:-gradle/libs.versions.toml}"

CENTRAL="https://repo1.maven.org/maven2"
GOOGLE="https://dl.google.com/dl/android/maven2"

python3 - "$CATALOG" > /tmp/coords.txt <<'PY'
import re
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()

section = None
versions = {}
coords = []

for raw in lines:
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    if line.startswith("["):
        section = line.strip("[]")
        continue
    if "=" not in line:
        continue
    key, value = (part.strip() for part in line.split("=", 1))
    value = value.strip().strip('"')

    if section == "versions":
        versions[key] = value
    elif section == "libraries":
        group = re.search(r'group\s*=\s*"([^"]+)"', value)
        name = re.search(r'name\s*=\s*"([^"]+)"', value)
        version = re.search(r'version\s*=\s*"([^"]+)"', value)
        ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', value)
        if not group or not name:
            continue
        resolved = None
        if version:
            resolved = version.group(1)
        elif ref:
            resolved = versions.get(ref.group(1))
        if not resolved:
            # Managed by a platform BOM: the BOM itself is checked instead.
            continue
        coords.append(f"{group.group(1)}:{name.group(1)}:{resolved}")

print("\n".join(coords))
PY

missing=0
checked=0

while IFS= read -r coord; do
    [ -z "$coord" ] && continue
    group="${coord%%:*}"
    rest="${coord#*:}"
    name="${rest%%:*}"
    version="${rest##*:}"
    path="${group//.//}/$name/$version/$name-$version.pom"

    status_central=$(curl -s -o /dev/null -w "%{http_code}" "$CENTRAL/$path")
    if [ "$status_central" != "200" ]; then
        status_google=$(curl -s -o /dev/null -w "%{http_code}" "$GOOGLE/$path")
        if [ "$status_google" != "200" ]; then
            echo "MISSING $coord (central=$status_central google=$status_google)"
            missing=$((missing + 1))
        fi
    fi
    checked=$((checked + 1))
done < /tmp/coords.txt

echo "Checked $checked coordinates, $missing missing."
if [ "$missing" -ne 0 ]; then
    exit 1
fi
