#!/usr/bin/env bash
# Prints the SHA-256 fingerprint of the certificate an APK or AAB was signed with.
#
# This is the fact the whole signing setup rests on. Everything else can be configured
# correctly and still produce an artifact signed with the wrong key - a property that did
# not reach Gradle, a keystore that failed to load and fell back to the debug key, a stale
# file in the output directory - and the only way to tell is to read the certificate out
# of the artifact itself.
#
# Two readers, because the two artifact types are signed differently:
#
#   * A **bundle** (and any JAR) is signed with the JAR signature scheme (v1), so the
#     certificate is in `META-INF/*.RSA` and `keytool -printcert -jarfile` reads it with no
#     password, no SDK and no extra tool.
#
#   * An **APK** built with `minSdk >= 24` is *not* JAR-signed. AGP states this as a rule
#     ("V1 signature is useless if minSdk is 24+") and drops v1 even when the signing
#     config asks for it, leaving the certificate in the APK Signing Block, which is
#     read by `apksigner` - part of the build-tools the project already installs. keytool
#     answers "Not a signed jar file" for such an APK, so a check that only knew keytool
#     would either fail on a correctly signed release or, worse, be relaxed until it
#     passed. This project's `minSdk` is 26, so this is the normal case.
#
# The preferred reader is tried first, then the other one, and the script says on stderr
# which one answered - a fallback nobody can see is a fallback that hides a mistake.
#
# Prints the fingerprint alone on stdout - upper-case hex, no colons, the format
# `ci/release-fingerprint.txt` uses - so a caller can compare the two directly. Anything
# explaining a failure goes to stderr.
#
# Usage: ci/apk-signer-fingerprint.sh <path-to-apk-or-aab>
set -uo pipefail

artifact="${1:-}"
if [ -z "$artifact" ]; then
    echo "usage: $0 <path-to-apk-or-aab>" >&2
    exit 2
fi
if [ ! -f "$artifact" ]; then
    echo "$artifact does not exist, so nothing could be read from it" >&2
    exit 1
fi

# ---------------------------------------------------------------- the two ways to read

via_keytool() {
    local certificates fingerprint signers
    # Needs no password: the certificate is public. Fails outright on an APK that carries
    # no v1 signature, which is why it is not the only reader.
    certificates="$(keytool -printcert -jarfile "$artifact" 2>/dev/null || true)"
    [ -n "$certificates" ] || return 0

    # Two signers would mean two entries; taking the first and saying so is better than
    # silently reporting one of them.
    signers="$(printf '%s\n' "$certificates" | grep -c "Certificate fingerprints:" || true)"
    if [ "$signers" -gt 1 ]; then
        echo "$artifact carries $signers signatures; this check reads the first" >&2
    fi

    # `|| true`: CI runs this file as `bash -e`, and a pipeline that finds nothing exits
    # non-zero, which would end the script at the line that was supposed to handle it.
    fingerprint="$(printf '%s\n' "$certificates" \
        | grep -A1 "SHA256:" \
        | grep -oE "([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}" \
        | head -1 \
        | tr -d ':' \
        | tr '[:lower:]' '[:upper:]' || true)"
    [ -n "$fingerprint" ] || return 0
    echo "  reader: keytool -printcert -jarfile (JAR signature, v1)" >&2
    printf '%s\n' "$fingerprint"
}

via_apksigner() {
    local -a candidates=()
    local tool output fingerprint

    # `apksigner` on PATH first, then the build-tools the SDK step installed. The newest
    # revision wins, because that is the one AGP itself would use.
    if command -v apksigner > /dev/null 2>&1; then
        candidates+=("$(command -v apksigner)")
    fi
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
        [ -n "$root" ] || continue
        [ -d "$root/build-tools" ] || continue
        while IFS= read -r found; do
            [ -n "$found" ] && candidates+=("$found")
        done < <(find "$root/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1)
    done

    for tool in "${candidates[@]:-}"; do
        [ -n "$tool" ] || continue
        # Non-zero exit means the artifact does not verify, which is a failure rather than
        # a different fingerprint: an APK whose signature is broken must not be reported
        # as carrying some certificate.
        output="$("$tool" verify --print-certs "$artifact" 2>&1)" || {
            echo "$tool could not verify $artifact:" >&2
            printf '%s\n' "$output" | head -5 >&2
            return 1
        }
        fingerprint="$(printf '%s\n' "$output" \
            | grep -i "certificate SHA-256 digest:" \
            | head -1 \
            | sed 's/.*digest: *//' \
            | tr -d ':' \
            | tr -d '[:space:]' \
            | tr '[:lower:]' '[:upper:]' || true)"
        if [ -n "$fingerprint" ]; then
            echo "  reader: $tool verify --print-certs (APK Signature Scheme v2/v3)" >&2
            printf '%s\n' "$fingerprint"
            return 0
        fi
    done
    return 0
}

# ------------------------------------------------------------------------- the choice
#
# By extension, because that is what the caller knows. A bundle and a plain JAR are
# JAR-signed, so keytool reads them; anything else is treated as an APK, where the
# certificate is in the APK Signing Block that apksigner reads.
#
# `.jar` used to fall through to apksigner, which is right for an APK and wrong for a
# JAR: on a CI runner apksigner exists, refuses a JAR ("ERROR: Missing
# AndroidManifest.xml"), and the script stopped there with an empty fingerprint - a
# failure that said "not signed" about an archive that was signed, and that could not be
# reproduced here, where no SDK is installed and apksigner is never found.
case "${artifact##*.}" in
    aab|AAB|jar|JAR) first=keytool; second=apksigner ;;
    *) first=apksigner; second=keytool ;;
esac

run_reader() {
    case "$1" in
        keytool) via_keytool ;;
        apksigner) via_apksigner ;;
    esac
}

fingerprint="$(run_reader "$first")" || exit 1
if [ -z "$fingerprint" ]; then
    fingerprint="$(run_reader "$second")" || exit 1
fi

if [ -z "$fingerprint" ]; then
    echo "$artifact: no certificate could be read from it." >&2
    echo "  keytool -printcert -jarfile : reads JAR-signed artifacts (a bundle, or an APK" >&2
    echo "                                whose minSdk is below 24)." >&2
    echo "  apksigner verify --print-certs: reads an APK's v2/v3 signature; it ships in the" >&2
    echo "                                Android SDK build-tools, which build.yml installs." >&2
    echo "  Neither found a certificate, so this is not a signed artifact at all, or the" >&2
    echo "  tools that can read it are missing from this machine." >&2
    exit 1
fi

printf '%s\n' "$fingerprint"
