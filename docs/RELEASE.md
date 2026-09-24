# Signing and releasing Memory Map

Nothing here is done yet: this document describes how to produce a signed release
once the feature phases are complete. **Do not describe the app as published
before it actually is.**

## 1) Create a signing key (once, keep it safe)

```bash
keytool -genkeypair -v \
  -keystore memorymap-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias memorymap
```

Back the keystore up outside this repository. The Android ecosystem ties your app
identity to this key: losing it means being unable to update the app.

## 2) Build a signed release

Never commit the keystore or its passwords. Pass them at build time:

```bash
./gradlew assembleRelease \
  -PMEMORYMAP_KEYSTORE=/absolute/path/memorymap-release.jks \
  -PMEMORYMAP_KEYSTORE_PASSWORD=... \
  -PMEMORYMAP_KEY_ALIAS=memorymap \
  -PMEMORYMAP_KEY_PASSWORD=...
```

`app/build.gradle.kts` reads those four properties into the
`releaseFromProperties` signing config. When they are absent, the release build
falls back to the debug key so that a CI build never fails for a missing secret —
which also means **an unsigned-by-design build is never mistaken for a real one**:
check the signing report before shipping.

```bash
./gradlew signingReport
```

## 3) What must be true before a release

- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew lintRelease` produces no errors.
- [ ] `bash ci/check-security.sh` passes. This is the gate that fails when a
      documented guarantee stops holding: a `service_role` key in the client, a
      log call that bypasses `MmLog`, `allowBackup` switched back on, cleartext
      traffic, a background location permission, minification turned off, or a
      log-stripping rule that no longer matches.
- [ ] `./gradlew assembleRelease` succeeds. It is the only build that runs R8, so
      it is the only one that can prove the shrinker rules are still valid.
- [ ] `versionCode` and `versionName` are bumped in `app/build.gradle.kts`.
- [ ] `CHANGELOG.md` has an entry for the version.
- [ ] The privacy policy matches what the build actually does.
- [ ] No `service_role` key appears anywhere in the source, in `local.properties`
      handling, or in the APK. Verify with:
      ```bash
      ./gradlew assembleRelease && \
        unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | \
        strings | grep -i service_role || echo "clean"
      ```
- [ ] The APK size is checked; large bundled media or fonts are justified.

Pushing a `v*` tag runs all of the above in `.github/workflows/release.yml`
before it publishes, so the checklist is enforced and not merely written down.

## 4) GitHub Releases

Pushing a tag does it:

```bash
git tag v1.0.0
git push origin v1.0.0
```

`.github/workflows/release.yml` runs the security gate, the unit tests and
`lintRelease`, builds both an APK and an AAB, and creates the GitHub Release
with the changelog as the notes.

Signing needs four repository secrets:

| Secret | Value |
|---|---|
| `MEMORYMAP_KEYSTORE_B64` | the keystore, `base64 -w0 memorymap-release.jks` |
| `MEMORYMAP_KEYSTORE_PASSWORD` | keystore password |
| `MEMORYMAP_KEY_ALIAS` | the alias |
| `MEMORYMAP_KEY_PASSWORD` | key password |

Without `MEMORYMAP_KEYSTORE_B64` the workflow still builds, but the artifact is
debug-signed and the release body says so in bold. That is deliberate: a
half-configured release should be obviously unusable rather than quietly
distributed.

To do it by hand instead:

```bash
./gradlew assembleRelease
gh release create v<version> \
  app/build/outputs/apk/release/app-release.apk \
  --title "Memory Map v<version>" \
  --notes-file CHANGELOG.md
```

## 5) F-Droid preparation

F-Droid builds from source, so the repository itself must be reproducible:

- Keep every dependency in `gradle/libs.versions.toml` with an exact version
  (already the case).
- Do not add a dependency on a binary blob or a prebuilt AAR hosted outside
  Maven Central / Google Maven.
- The Supabase URL and anon key must come from `local.properties`, never from a
  committed file, so an F-Droid build stays offline-only by default.
- Add an F-Droid metadata file under `fastlane/metadata/android/` when the first
  user-facing release is cut (not before, to avoid metadata for a version that
  does not exist).

## 6) Google Play preparation

- Produce an AAB instead of an APK: `./gradlew bundleRelease`.
- Upload with Play App Signing enabled.
- Complete the Data safety form honestly: this app collects nothing by default,
  and when Supabase is connected it stores account email and the user's own
  content, encrypted in transit.
- Provide the privacy policy URL (the English copy in `docs/legal/PRIVACY_EN.md`
  rendered anywhere public is enough).

## 7) Free tier limits to document per release

Before each release, re-check the quotas of every external service the build
talks to, and record them in the changelog so nobody assumes `free = unlimited`:

| Service | What to check |
|---|---|
| Supabase Storage | total bytes and egress per month |
| Supabase Database | row count and database size |
| Supabase Auth | monthly active users |
| Map tiles | request rate and the provider's usage policy |
