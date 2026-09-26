# Signing and releasing Memory Map

The pipeline is prepared and rehearsed; **the app is not published anywhere**, on
GitHub Releases or on any store, and it must not be described as published before
that actually happens. Everything below can be executed exactly as written.

The first version this document is written for is `0.1.0` (`versionCode` 1).

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

## 3b) Rehearse the release without publishing

Run the same workflow by hand and it does everything except publish:

```bash
gh workflow run release.yml --ref main
gh run list --workflow release.yml --limit 1
```

That only works once `release.yml` is on the default branch: GitHub registers
`workflow_dispatch` for workflows in the default branch, and a dispatch from any
other branch is answered with `HTTP 404 .../actions/workflows/release.yml`.
Until then the one thing that runs this file is a `v*` tag push, which
publishes - so do not use a tag as a rehearsal.

The AAB path is verified without any of that: `build.yml` runs
`assembleRelease bundleRelease` on every push, reports the size of both
artifacts, and fails when either is missing, so the artifact Google Play
receives is built on every commit rather than first at release time.

It runs the security gate, the store metadata check, the unit tests and
`lintRelease`, builds the APK *and* the AAB, prints the size of what a tag push
would attach, and stops. Only `refs/tags/v*` reaches the publishing step, so a
mis-clicked run cannot create a public release.

Check the version before a real run:

```bash
grep -n "versionCode\|versionName" app/build.gradle.kts
python3 ci/check-store-metadata.py     # also checks changelogs/<versionCode>.txt exists
```

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
- The F-Droid metadata is `fastlane/metadata/android/` (the layout F-Droid reads
  as `fastlane/metadata/android/<locale>/`), written for `versionCode` 1 in
  Arabic (`ar`) and English (`en-US`). Every release adds
  `changelogs/<versionCode>.txt`; `ci/check-store-metadata.py` fails the build
  when that file is missing or over the 500-character limit F-Droid and Google
  Play both enforce.
- Screenshots are still missing, and they have to come from a real device running
  the real build: F-Droid shows what it is given, and a mock-up would be a lie
  about what the app looks like. Two to eight per locale, under
  `fastlane/metadata/android/<locale>/images/phoneScreenshots/`.
- `fastlane/metadata/android/<locale>/images/icon.png` (512x512) and
  `featureGraphic.png` (1024x500) are also still missing; the icon should be the
  adaptive icon exported, not a new drawing, so the store and the launcher show
  the same mark.

## 6) Google Play preparation

- Produce an AAB instead of an APK: `./gradlew bundleRelease` (the release
  workflow already does this).
- Upload with Play App Signing enabled.
- The listing text is `fastlane/metadata/android/{ar,en-US}/`, and `fastlane`
  with `supply` uploads it if you ever want that automated. Automation needs a
  Google Cloud service account with access to the Play Console, which is a
  credential this repository deliberately does not hold; the console upload is
  the default path.
- Provide the privacy policy URL (the English copy in `docs/legal/PRIVACY_EN.md`
  rendered anywhere public is enough).
- Target API level: `targetSdk = 36`.

### Data safety form

The answers below are what the app actually does, and they are worth re-reading
against `AndroidManifest.xml` and `ci/check-security.sh` before submitting:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | No, not in the default offline build |
| Data collected when Supabase sync is enabled by the user | Email (account), and the user's own content: memories, diary entries, media, people, places |
| Is this data shared with third parties? | No. It goes to the Supabase project the user creates and owns |
| Is data encrypted in transit? | Yes, HTTPS only; cleartext traffic is disabled |
| Can users request data deletion? | Yes, in-app: delete the account and its data, and delete the uploaded copies |
| Location | Collected only when the user presses for it; never in the background |
| Advertising or tracking identifiers | None |

### Content rating and audience

- No ads, no in-app purchases, no user-to-user features, no user-generated
  public content, no external links to unrestricted content.
- The app is a personal diary: the rating questionnaire should be answered as a
  utility, with no violence, no sexual content, no gambling, no profanity.
- Target audience: adults. Nothing in the app is designed for children, and no
  data is collected from anyone by default.

## 7) Free tier limits to document per release

Before each release, re-check the quotas of every external service the build
talks to, and record them in the changelog so nobody assumes `free = unlimited`:

| Service | What to check |
|---|---|
| Supabase Storage | total bytes and egress per month |
| Supabase Database | row count and database size |
| Supabase Auth | monthly active users |
| Map tiles | request rate and the provider's usage policy |
