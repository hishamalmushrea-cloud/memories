#!/usr/bin/env bash
# Exercises the tools that decide whether a release is signed with the right key.
#
# The certificate check is the last thing standing between a build that succeeded and a
# release that is wrong, so it is not allowed to be the part nobody has ever run. Until
# this file existed, `ci/verify-apk-signature.sh` first executed on the run that creates a
# release - the one run that cannot be repeated, because a GitHub release and a Play
# upload are visible.
#
# What is checked, and why each one is different from the others:
#
#   1. A JAR signed with a known key is read back with the right fingerprint. This is the
#      bundle path: an AAB is a JAR, and keytool reads its certificate. The signature is
#      written by `jarsigner` when the JDK has it (CI does) and by a built-in Python
#      fallback otherwise (this workspace's JDK image ships the runtime only), and the
#      structure is the JAR signing scheme either way.
#   2. That archive is refused by `--release` and accepted by `--rehearsal`. The two modes
#      are the load-bearing pair: the first says "this must be our key", the second says
#      "this must not be", and a mode that accepts both would let a rehearsal artifact
#      pass as a release or the reverse.
#   3. An unsigned archive fails rather than reporting an empty fingerprint. An empty
#      value compared against anything is how a check passes for the wrong reason.
#   4. The APK path is parsed correctly. An APK built at minSdk 26 has no JAR signature -
#      AGP drops v1 above 23 ("V1 signature is useless if minSdk is 24+") - so apksigner
#      reads the APK Signing Block instead. apksigner lives in the build-tools, which this
#      job installs after this step runs, and it needs an APK this step cannot build, so
#      the stand-in here prints exactly the output Android documents. The stand-in proves
#      the parsing and the choice between readers; the real binary runs on the real APK a
#      few steps later in this same job.
#   5. With neither reader available the script fails instead of printing nothing.
#
# Every check prints what it did, so a failure says which of the five stopped working.
#
# Written for `bash -e`, which is how a workflow `run:` step is executed: several of the
# commands below are expected to exit non-zero, so none of them is left to report its own
# failure. Run it as `bash -e ci/check-signing-tools.sh` to reproduce CI exactly.
#
# Usage: bash ci/check-signing-tools.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

problems=0
fail() {
    echo "FAIL: $1"
    problems=$((problems + 1))
}

# Three helpers, because GitHub Actions runs a `run:` step as `bash -e`: a command that
# is *supposed* to fail ends the script before the line that would have reported it. This
# check first passed in this workspace, where it was run as plain `bash`, and failed in CI
# at its second assertion - the one that expects `--release` to refuse something. Neither
# a command nor a command substitution below is allowed to be the last word on its own
# failure.
expect_success() {  # <description> <command...>
    local description="$1"
    shift
    if "$@" > /dev/null 2>&1; then
        echo "   ok   $description"
    else
        fail "$description (it failed, and it must not)"
    fi
}

expect_failure() {  # <description> <command...>
    local description="$1"
    shift
    if "$@" > /dev/null 2>&1; then
        fail "$description (it succeeded, and it must not)"
    else
        echo "   ok   $description"
    fi
}

run_captured() {  # <variable> <command...> - never lets the command's status escape
    local target="$1"
    shift
    local output
    output="$("$@" 2>/dev/null)" || true
    printf -v "$target" '%s' "$output"
}

if ! command -v keytool > /dev/null 2>&1; then
    echo "FAIL: keytool is not on PATH; the JDK that builds the app provides it"
    exit 1
fi

PASSWORD="$(python3 -c "
import secrets, string
alphabet = string.ascii_letters + string.digits
print(''.join(secrets.choice(alphabet) for _ in range(32)))
")"
KEYSTORE="$WORK/signing-tools.jks"
ALIAS=signtools

if ! keytool -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -keyalg RSA -keysize 2048 -validity 1 \
    -alias "$ALIAS" -storepass "$PASSWORD" \
    -dname "CN=Memory Map guard, OU=CI, O=Memory Map, C=YE" > /dev/null 2>&1; then
    echo "FAIL: could not generate a throwaway keystore, so nothing below could be checked"
    exit 1
fi

# `|| true` because `head` closing the pipe can leave `grep` with SIGPIPE, and under
# `set -o pipefail` that is a non-zero pipeline - which `-e` turns into the end of the
# script, at the line that was going to explain that no fingerprint was found.
expected="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" 2>/dev/null \
    | grep -oE "([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}" | head -1 | tr -d ':' \
    | tr '[:lower:]' '[:upper:]' || true)"
if [ -z "$expected" ]; then
    echo "FAIL: keytool generated a keystore whose fingerprint it cannot print"
    exit 1
fi
echo "   the throwaway certificate: $expected"

# ------------------------------------------------------------------ a signed JAR archive
# Every path below is absolute, and that is not a style choice: the first version of this
# step ran `jar` inside `(cd "$WORK" && ...)` and then handed the *relative* name to
# `jarsigner`, which therefore looked for it in the repository root, failed, fell through
# to the fallback - and the fallback needs a Python module the runner does not have. The
# check failed in CI twice with no message that named any of that. Nothing here depends on
# the working directory.
printf 'nothing to see\n' > "$WORK/payload.txt"
JAR="$WORK/signed.jar"

signed_by=""
if command -v jar > /dev/null 2>&1 && command -v jarsigner > /dev/null 2>&1; then
    # The key password is not passed: this is a PKCS12 store, where the two passwords are
    # one and `-keypass` is ignored with a warning.
    #
    # Why the tool's own output is echoed when it fails: this is the path CI takes and the
    # path this workspace cannot take (its JDK image has no jarsigner), so a failure here
    # has to explain itself in the log of the only run that can hit it. A silent fallback
    # would replace a diagnosis with a second failure.
    if jar cf "$JAR" -C "$WORK" payload.txt 2> "$WORK/jar-error"; then
        if jarsigner -keystore "$KEYSTORE" -storepass "$PASSWORD" \
            -sigalg SHA256withRSA -digestalg SHA-256 \
            "$JAR" "$ALIAS" > /dev/null 2> "$WORK/jarsigner-error"; then
            # Signed is not the same as signed *correctly*: `jarsigner` exiting zero says
            # it wrote something, not that it wrote a signature this JDK can read back.
            # The first check below reads it, but a JAR that is not a signed JAR at all
            # would be reported as "the reader returned nothing", which describes the
            # reader rather than the signing step that failed.
            if python3 -c "
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
# jarsigner names the block after the alias; the alias may be lower case in the file
sys.exit(0 if any(n.startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC')) for n in names) else 1)
" "$JAR"; then
                signed_by="jarsigner"
            else
                echo "   jarsigner reported success but $JAR carries no signature block"
            fi
        else
            echo "   jarsigner failed, and its own message follows:"
            sed 's/^/   | /' "$WORK/jarsigner-error" | head -5 || true
        fi
    else
        echo "   jar failed, and its own message follows:"
        sed 's/^/   | /' "$WORK/jar-error" | head -5 || true
    fi
fi

if [ -z "$signed_by" ]; then
    # The fallback writes the same structure - a manifest with per-entry digests, a
    # signature file, and a detached PKCS#7 block in META-INF/CERT.RSA - using the
    # keystore's own key. It exists because this workspace's JDK image has no jarsigner.
    # The fallback is for a workstation whose JDK image has no jarsigner; CI takes the
    # branch above. If neither is usable, that is a failure of this check rather than a
    # reason to skip it.
    if ! python3 - "$KEYSTORE" "$PASSWORD" "$JAR" <<'PY'
import base64, hashlib, sys, zipfile

keystore, password, output = sys.argv[1:4]
try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.serialization import pkcs7, pkcs12
except ImportError:
    sys.exit("neither jarsigner nor the cryptography module is available")

with open(keystore, "rb") as handle:
    key, certificate, _ = pkcs12.load_key_and_certificates(handle.read(), password.encode())
if key is None or certificate is None:
    sys.exit("the keystore holds no private key and certificate")


def section(headers):
    return ("".join(f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n").encode()


payload = b"nothing to see\n"
manifest = section({"Manifest-Version": "1.0", "Created-By": "check-signing-tools"})
manifest += section({
    "Name": "payload.txt",
    "SHA-256-Digest": base64.b64encode(hashlib.sha256(payload).digest()).decode(),
})
signature_file = section({
    "Signature-Version": "1.0",
    "SHA-256-Digest-Manifest": base64.b64encode(hashlib.sha256(manifest).digest()).decode(),
})
block = (
    pkcs7.PKCS7SignatureBuilder()
    .set_data(signature_file)
    .add_signer(certificate, key, hashes.SHA256())
    .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature])
)
with zipfile.ZipFile(output, "w") as archive:
    archive.writestr("payload.txt", payload)
    archive.writestr("META-INF/MANIFEST.MF", manifest)
    archive.writestr("META-INF/SIGNER.SF", signature_file)
    archive.writestr("META-INF/SIGNER.RSA", block)
PY
    then
        fail "no jarsigner and no working fallback, so no signed archive could be built"
    else
        signed_by="the Python fallback"
    fi
fi
echo "   the signed archive was produced by: ${signed_by:-nothing}"

# ---------------------------------------------------------------------------- the checks
if [ -f "$JAR" ]; then
    run_captured actual bash "$ROOT/ci/apk-signer-fingerprint.sh" "$JAR"
    if [ "$actual" = "$expected" ]; then
        echo "   ok   1. the reader found $actual in the signed archive"
    else
        fail "the reader returned '$actual' for an archive signed with $expected"
        # What the reader saw, in the reader's own words. "The reader returned nothing"
        # describes the reader; the question is whether the archive is not signed, or its
        # signature is not one this JDK will read. A CI failure with no way to tell those
        # apart is what this block exists to prevent, and the first version of this check
        # produced exactly that.
        echo "   keytool says:"
        keytool -printcert -jarfile "$JAR" 2>&1 | head -6 | sed 's/^/   | /' || true
        echo "   the archive contains:"
        python3 -c "
import sys, zipfile
for info in zipfile.ZipFile(sys.argv[1]).infolist():
    print(f'{info.filename} ({info.file_size} bytes)')" "$JAR" 2>&1 | sed 's/^/   | /' || true
        echo "   the signature material holds:"
        python3 -c "
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in archive.namelist():
        if name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')):
            print(f'{name}: {len(archive.read(name))} bytes')" "$JAR" 2>&1 | sed 's/^/   | /' || true
    fi

    expect_failure "2a. --release refuses a certificate that is not the release one" \
        bash "$ROOT/ci/verify-apk-signature.sh" --release "$JAR"
    expect_success "2b. --rehearsal accepts it" \
        bash "$ROOT/ci/verify-apk-signature.sh" --rehearsal "$JAR"
fi

UNSIGNED="$WORK/unsigned.jar"
python3 -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w') as archive:
    archive.writestr('payload.txt', 'x')" "$UNSIGNED"
expect_failure "3. an unsigned archive is a failure, not an empty fingerprint" \
    bash "$ROOT/ci/apk-signer-fingerprint.sh" "$UNSIGNED"

# ------------------------------------------------------------------- the APK path, parsed
FAKEBIN="$WORK/fakebin"
mkdir -p "$FAKEBIN"
cat > "$FAKEBIN/apksigner" <<'FAKE'
#!/usr/bin/env bash
# The output Android documents for `apksigner verify --print-certs`, with the digest read
# from a file so this check can vary it. The real apksigner runs on the real APK later in
# this job; what is being checked here is the reading of this output.
if [ -f "$SIGNING_TOOLS_APKSIGNER_FAIL" ]; then
    echo "DOES NOT VERIFY"
    echo "ERROR: No signature found"
    exit 1
fi
echo "Verifies"
echo "Verified using v1 scheme (JAR signing): false"
echo "Verified using v2 scheme (APK Signature Scheme v2): true"
echo "Verified using v3 scheme (APK Signature Scheme v3): true"
echo "Number of signers: 1"
echo "Signer #1 certificate DN: CN=Memory Map guard, OU=CI, O=Memory Map, C=YE"
echo "Signer #1 certificate SHA-256 digest: $(printf '%s' "$(cat "$SIGNING_TOOLS_APKSIGNER_DIGEST")" | tr 'A-F' 'a-f')"
echo "Signer #1 certificate SHA-1 digest: 0000000000000000000000000000000000000000"
FAKE
chmod +x "$FAKEBIN/apksigner"
export SIGNING_TOOLS_APKSIGNER_DIGEST="$WORK/apksigner-digest"
export SIGNING_TOOLS_APKSIGNER_FAIL="$WORK/apksigner-fails"
printf '%s' "$expected" > "$SIGNING_TOOLS_APKSIGNER_DIGEST"

# The stand-in is on PATH for the rest of this file, so every call below takes the same
# route the real APK takes in the step that verifies the built artifact. Setting it for
# one command only is how this check first passed for the wrong reason: without it, the
# reader fell back to keytool, read the archive as if it were a JAR, and reported the
# expected fingerprint while apksigner had never run.
export PATH="$FAKEBIN:$PATH"

# An APK is read by apksigner, so the artifact is named the way an APK is named.
cp "$JAR" "$WORK/app-release.apk" 2>/dev/null || python3 -c "
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])" "$JAR" "$WORK/app-release.apk"
run_captured actual bash "$ROOT/ci/apk-signer-fingerprint.sh" "$WORK/app-release.apk"
if [ "$actual" = "$expected" ]; then
    echo "   ok   4a. the APK path is read with apksigner and parsed correctly"
else
    fail "the apksigner output was not parsed: got '$actual'"
fi
bash "$ROOT/ci/apk-signer-fingerprint.sh" "$WORK/app-release.apk" > /dev/null 2> "$WORK/reader.log"
if grep -q "reader: .*apksigner" "$WORK/reader.log"; then
    echo "   ok   4b. and the log says apksigner answered, so the fallback was not silent"
else
    fail "the APK was not read with apksigner (log: $(sed 's/^ *//' < "$WORK/reader.log" | head -1))"
fi

printf '%s' "$(printf 'AB%.0s' $(seq 32))" > "$SIGNING_TOOLS_APKSIGNER_DIGEST"
expect_failure "4c. a different certificate in the APK fails --release" \
    bash "$ROOT/ci/verify-apk-signature.sh" --release "$WORK/app-release.apk"

touch "$SIGNING_TOOLS_APKSIGNER_FAIL"
expect_failure "4d. a signature that does not verify is an error" \
    bash "$ROOT/ci/apk-signer-fingerprint.sh" "$WORK/app-release.apk"
rm -f "$SIGNING_TOOLS_APKSIGNER_FAIL"

# ------------------------------------------------------------- with neither tool present
BARE="$WORK/bare"
mkdir -p "$BARE"
for tool in bash env cat grep sed head tail cut sort find rm mkdir cp touch python3 mktemp tr seq; do
    link="$(command -v "$tool" 2>/dev/null)" && ln -sf "$link" "$BARE/$tool"
done
expect_failure "5. with no keytool and no apksigner it fails instead of printing nothing" \
    env PATH="$BARE" bash "$ROOT/ci/apk-signer-fingerprint.sh" "$JAR"

echo
if [ "$problems" -eq 0 ]; then
    echo "signing tools check: PASS (the certificate is read back from a signed archive, both"
    echo "                     modes refuse what they must, and an unsigned artifact fails)"
    exit 0
fi
echo "signing tools check: FAIL ($problems problem(s))"
exit 1
