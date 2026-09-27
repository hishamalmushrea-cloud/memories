#!/usr/bin/env bash
# Puts a keystore where a release build can use it, and says which one it was.
#
# Two situations, and the difference matters:
#
#   * The repository secrets are present. The keystore is decoded from
#     `MEMORYMAP_KEYSTORE_B64` and - before anything is built - its certificate is
#     compared with `ci/release-fingerprint.txt`. A secret that holds a different key, a
#     truncated base64 value, a wrong password: all three are caught here, in a second,
#     instead of after a five-minute build that produces an artifact signed by the wrong
#     key. The alias and password are checked by opening the store, not by trusting that
#     they were typed correctly somewhere else.
#
#   * They are not. A throwaway keystore is generated so the rehearsal still exercises the
#     property plumbing end to end. A rehearsal that skips signing proves nothing about
#     the signing path; this one proves that `-PMEMORYMAP_KEYSTORE=...` reaches Gradle and
#     changes the artifact's certificate, which is the failure that would otherwise be
#     discovered on release day.
#
# Writes `/tmp/signing/signing.env` (mode 600, on the runner's ephemeral disk):
#
#   SIGNING_MODE                  release | rehearsal
#   SIGNING_KEYSTORE              path to the keystore
#   SIGNING_STORE_PASSWORD        store password
#   SIGNING_KEY_ALIAS             alias
#   SIGNING_KEY_PASSWORD          key password
#   SIGNING_EXPECTED_FINGERPRINT  what the built artifact must carry
#
# and prints one line saying which mode it is, because a person reading the log of a
# release run should not have to infer whether the artifact is real.
#
# Never prints a password. Callers pass them to Gradle through the environment.
#
# Usage: ci/prepare-signing-keystore.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR=/tmp/signing
mkdir -p "$DIR"
chmod 700 "$DIR"
ENV_FILE="$DIR/signing.env"
rm -f "$ENV_FILE"

fingerprint_of() {
    local keystore="$1" password="$2" alias="$3"
    keytool -list -v -keystore "$keystore" -storepass "$password" -alias "$alias" 2>/dev/null \
        | grep -oE "([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}" \
        | head -1 \
        | tr -d ':' \
        | tr '[:lower:]' '[:upper:]'
}

# `|| true` on every command substitution here: a workflow `run:` step is executed with
# `bash -e`, so a pipeline that finds nothing would end the script at the line written to
# explain that nothing was found.
released="$(grep -E "^SHA256=" "$ROOT/ci/release-fingerprint.txt" | tail -1 | cut -d= -f2 \
    | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' || true)"
if [ -z "$released" ]; then
    echo "ci/release-fingerprint.txt has no SHA256= line" >&2
    exit 1
fi

if [ -n "${KEYSTORE_B64:-}" ]; then
    # ---------------------------------------------------------------- the real one
    for required in KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
        if [ -z "${!required:-}" ]; then
            echo "$required is not set, so the keystore cannot be opened" >&2
            echo "All four signing secrets are needed together; see docs/RELEASE.md." >&2
            exit 1
        fi
    done

    KEYSTORE="$DIR/memorymap-release.jks"
    printf '%s' "$KEYSTORE_B64" | base64 -d > "$KEYSTORE" 2>/dev/null || {
        echo "MEMORYMAP_KEYSTORE_B64 is not valid base64" >&2
        exit 1
    }
    chmod 600 "$KEYSTORE"

    actual="$(fingerprint_of "$KEYSTORE" "$KEYSTORE_PASSWORD" "$KEY_ALIAS" || true)"
    if [ -z "$actual" ]; then
        echo "the keystore could not be opened with the password and alias supplied:" >&2
        echo "  alias: $KEY_ALIAS" >&2
        echo "  (the password is not printed)" >&2
        echo "Check MEMORYMAP_KEYSTORE_PASSWORD, MEMORYMAP_KEY_ALIAS and MEMORYMAP_KEY_PASSWORD." >&2
        exit 1
    fi
    if [ "$actual" != "$released" ]; then
        echo "the secret holds a different key from the one this repository records:" >&2
        echo "  in the secret                     : $actual" >&2
        echo "  in ci/release-fingerprint.txt     : $released" >&2
        echo "The build would produce an artifact Play will refuse. Nothing was built." >&2
        exit 1
    fi

    {
        echo "SIGNING_MODE=release"
        echo "SIGNING_KEYSTORE='$KEYSTORE'"
        echo "SIGNING_STORE_PASSWORD='$KEYSTORE_PASSWORD'"
        echo "SIGNING_KEY_ALIAS='$KEY_ALIAS'"
        echo "SIGNING_KEY_PASSWORD='$KEY_PASSWORD'"
        echo "SIGNING_EXPECTED_FINGERPRINT='$actual'"
    } > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "signing: the repository secrets are present and match the recorded certificate"
    echo "signing:   $actual"
    exit 0
fi

# ------------------------------------------------------------------- the rehearsal
KEYSTORE="$DIR/rehearsal.jks"
rm -f "$KEYSTORE"
PASSWORD="$(python3 -c "
import secrets, string
alphabet = string.ascii_letters + string.digits
print(''.join(secrets.choice(alphabet) for _ in range(32)))
")"
ALIAS="rehearsal"

if ! keytool -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -keyalg RSA -keysize 2048 -validity 1 \
    -alias "$ALIAS" -storepass "$PASSWORD" \
    -dname "CN=Memory Map rehearsal, OU=CI, O=Memory Map, C=YE" >/dev/null 2>&1; then
    echo "could not generate a rehearsal keystore" >&2
    exit 1
fi
chmod 600 "$KEYSTORE"

actual="$(fingerprint_of "$KEYSTORE" "$PASSWORD" "$ALIAS" || true)"
if [ -z "$actual" ]; then
    echo "the rehearsal keystore was generated but cannot be opened again" >&2
    exit 1
fi
if [ "$actual" = "$released" ]; then
    # Statistically impossible, and worth failing on rather than reasoning about.
    echo "the generated rehearsal key has the release certificate's fingerprint" >&2
    exit 1
fi

{
    echo "SIGNING_MODE=rehearsal"
    echo "SIGNING_KEYSTORE='$KEYSTORE'"
    echo "SIGNING_STORE_PASSWORD='$PASSWORD'"
    echo "SIGNING_KEY_ALIAS='$ALIAS'"
    echo "SIGNING_KEY_PASSWORD='$PASSWORD'"
    echo "SIGNING_EXPECTED_FINGERPRINT='$actual'"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "signing: no secrets, so this is a rehearsal with a throwaway key"
echo "signing:   $actual"
