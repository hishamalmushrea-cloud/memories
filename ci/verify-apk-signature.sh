#!/usr/bin/env bash
# Checks that a built APK carries the certificate it is supposed to carry.
#
# Two questions, one script, because they are the two halves of the same guarantee:
#
#   --release    the artifact must carry the certificate in `ci/release-fingerprint.txt`.
#                This is the claim "assembleRelease used the real signing key", and it is
#                checked against the artifact rather than against the configuration that
#                was supposed to produce it. A property that never reached Gradle, a
#                keystore that failed to load, a signing config that quietly fell back to
#                the debug key: all three produce a build that succeeds and an APK that is
#                wrong, and only the certificate tells them apart.
#
#   --rehearsal  the artifact must NOT carry that certificate. A run without the secrets
#                is a rehearsal that must be recognisable as one; an APK that looks like a
#                release while being signed by a throwaway key is exactly the artifact
#                that should never reach anybody.
#
# Usage: ci/verify-apk-signature.sh --release|--rehearsal <path-to-apk> [expected-fingerprint]
#
# The optional third argument is for the rehearsal case in CI, where the keystore is
# generated on the runner: passing its fingerprint turns "not the release key" into "the
# key this build was actually given", which is a much stronger statement - it proves the
# signing properties reach Gradle at all.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FINGERPRINT_FILE="$ROOT/ci/release-fingerprint.txt"

mode="${1:-}"
artifact="${2:-}"
expected_override="${3:-}"

if [ "$mode" != "--release" ] && [ "$mode" != "--rehearsal" ]; then
    echo "usage: $0 --release|--rehearsal <path-to-apk> [expected-fingerprint]" >&2
    exit 2
fi
if [ -z "$artifact" ] || [ ! -f "$artifact" ]; then
    echo "usage: $0 --release|--rehearsal <path-to-apk> [expected-fingerprint]" >&2
    echo "(no artifact at '${artifact:-<empty>}', so there is nothing to verify)" >&2
    exit 1
fi

released="$(grep -E "^SHA256=" "$FINGERPRINT_FILE" | tail -1 | cut -d= -f2 | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
if [ -z "$released" ]; then
    echo "$FINGERPRINT_FILE has no SHA256= line, so there is nothing to compare against" >&2
    exit 1
fi

actual="$(bash "$ROOT/ci/apk-signer-fingerprint.sh" "$artifact")" || exit 1
if [ -z "$actual" ]; then
    echo "could not read a certificate out of $artifact" >&2
    exit 1
fi

case "$mode" in
    --release)
        if [ "$actual" != "$released" ]; then
            echo "FAIL: $artifact is signed with the wrong key"
            echo "      certificate in the artifact : $actual"
            echo "      the release certificate     : $released"
            echo "      Nothing was published. Either the signing secrets are not the ones"
            echo "      this repository records, or the build fell back to another key."
            exit 1
        fi
        echo "signature check: PASS - $artifact carries the release certificate"
        echo "  $actual"
        ;;
    --rehearsal)
        if [ -n "$expected_override" ]; then
            expected="$(printf '%s' "$expected_override" | tr -d ':' | tr '[:lower:]' '[:upper:]')"
            if [ "$actual" != "$expected" ]; then
                echo "FAIL: $artifact is not signed with the key this run generated"
                echo "      certificate in the artifact : $actual"
                echo "      the key handed to the build : $expected"
                echo "      The signing properties are not reaching Gradle."
                exit 1
            fi
            echo "signature check: PASS - the build signed with the key it was given"
            echo "  $actual"
            exit 0
        fi
        if [ "$actual" = "$released" ]; then
            echo "FAIL: $artifact carries the release certificate, but this run had no"
            echo "      signing secrets. Either the secrets are present after all, or a"
            echo "      committed key signed it - neither is what a rehearsal should do."
            exit 1
        fi
        echo "signature check: PASS - not release-signed, as a rehearsal must be"
        echo "  $actual"
        ;;
esac
