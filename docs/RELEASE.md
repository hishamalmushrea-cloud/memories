# Signing and releasing Memory Map

The pipeline is prepared and rehearsed; **the app is not published anywhere**, on
GitHub Releases or on any store, and it must not be described as published before
that actually happens. Everything below can be executed exactly as written.

The first version this document is written for is `0.1.0` (`versionCode` 1).

## 1) Create a signing key (once, keep it safe)

This is the command the release key of this project was created with, on JDK 17:

```bash
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore memorymap-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias memorymap \
  -dname "CN=Memory Map, OU=Mobile, O=Memory Map, L=<city>, ST=<region>, C=<country>"
```

Two things about that command are worth knowing before you run it:

- **`-storetype PKCS12` is what JDK 17 does by default**, and PKCS12 does not have
  a separate key password: the store and the private key share one. A `-keypass`
  is ignored with a warning ("Different store and key passwords not supported for
  PKCS12 KeyStores"), and the resulting file cannot be opened with the password
  you thought you set. So the project uses **one password** for both, and
  `MEMORYMAP_KEYSTORE_PASSWORD` and `MEMORYMAP_KEY_PASSWORD` hold the same value.
  The build keeps the fourth property for JKS keystores, where the two are
  genuinely separate.
- The validity is deliberately long (10000 days, ~27 years). The app's identity is
  this certificate, so a renewal is not a renewal: Play only accepts an update
  signed with the same certificate, unless Play App Signing holds an upload key
  that can be reset.

Back the keystore up outside this repository, in two places, and write the
password down where you keep the file. Losing the key means being unable to update
the app under its own name - and no later commit can undo a key that leaks, which
is why `ci/check-keystore-leaks.py` reads the Git index for one on every push.

Record the certificate in the repository so a build can be checked against it
(the value below is this project's; yours will differ):

```bash
keytool -list -v -keystore memorymap-release.jks -alias memorymap | grep SHA256
# then put it in ci/release-fingerprint.txt as: SHA256=<64 uppercase hex digits>
```

`ci/release-fingerprint.txt` holds only the public certificate fingerprint, so it
is committed on purpose. If it ever changes, the app's identity has changed and
Google Play will refuse a differently-signed upload - treat the diff as a release
blocker, not as a detail.

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
`releaseFromProperties` signing config. Locally, when they are absent, the release
build falls back to the debug key - that is convenient when you only want to see
that R8 accepts the code, and it is why the artifact has to be checked rather than
assumed. To see which key a build actually used:

```bash
./gradlew signingReport
# the certificate inside a finished artifact, no password needed:
bash ci/apk-signer-fingerprint.sh app/build/outputs/apk/release/app-release.apk
bash ci/verify-apk-signature.sh --release app/build/outputs/apk/release/app-release.apk
```

The second command is the one that matters before publishing: with `--release` it
fails unless the artifact carries the certificate recorded in
`ci/release-fingerprint.txt`. `/tmp/signing/signing.env`, written by
`ci/prepare-signing-keystore.sh` when a release runs, is read-only to the run and
holds no password that survives it.

**In CI the fallback cannot reach a release.** A `v*` tag with fewer than all four
secrets fails before Gradle starts (see §4); a manual run without them builds a
rehearsal artifact with a generated throwaway key, and `ci/verify-apk-signature.sh
--rehearsal` fails unless that artifact is signed with something *other* than the
release certificate. There is no configuration in which a tag publishes a
debug-signed artifact.

## 3) What must be true before a release

- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew lintRelease` produces no errors.
- [ ] `bash ci/check-signing-tools.sh` passes. It checks the two tools that read a
      signature rather than the artifact itself, because the artifact check first ran
      on a release run until this existed.
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
- [ ] The artifact carries the release certificate:
      `bash ci/verify-apk-signature.sh --release app/build/outputs/apk/release/app-release.apk`
- [ ] `python3 ci/check-keystore-leaks.py` passes. It reads the Git index, not the
      working tree, so an untracked local keystore is not noise - and a file added
      with `git add -f` is still a failure. `bash ci/check-security.sh` runs it.

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
`lintRelease`, builds both an APK and an AAB, **checks the certificate inside
them**, and creates the GitHub Release with the changelog as the notes.

Signing needs four repository secrets. Set them once, before the first tag:

| Secret | Value |
|---|---|
| `MEMORYMAP_KEYSTORE_B64` | the keystore, `base64 -w0 memorymap-release.jks` (one line, no newline) |
| `MEMORYMAP_KEYSTORE_PASSWORD` | the keystore password |
| `MEMORYMAP_KEY_ALIAS` | the alias, `memorymap` for the key in §1 |
| `MEMORYMAP_KEY_PASSWORD` | the same password as the store, because PKCS12 has one (see §1) |

```bash
base64 -w0 memorymap-release.jks > keystore.b64      # then paste the file's contents
gh secret set MEMORYMAP_KEYSTORE_B64      --repo <owner>/<repo> < keystore.b64
gh secret set MEMORYMAP_KEYSTORE_PASSWORD --repo <owner>/<repo>   # prompts, or < file
gh secret set MEMORYMAP_KEY_ALIAS         --repo <owner>/<repo>
gh secret set MEMORYMAP_KEY_PASSWORD      --repo <owner>/<repo>
```

`gh secret set` needs admin rights on the repository; the web UI (Settings →
Secrets and variables → Actions) does the same thing without them. Never paste a
secret value into an issue, a pull request, a chat or a log - the value is the key.

**What the workflow does with them** (`ci/check-release-signing.py` is the policy,
and `build.yml` exercises it against all six situations on every push):

| Ref | Secrets | Result |
|---|---|---|
| `v*` tag | all four | builds, and the artifact's certificate must equal `ci/release-fingerprint.txt` |
| `v*` tag | some | fails before Gradle starts, naming the missing secret |
| `v*` tag | none | fails before Gradle starts - a release is not a rehearsal |
| any other ref | all four | builds and verifies against the release certificate, publishes nothing |
| any other ref | some | fails: half a configuration is never intentional |
| any other ref | none | builds a rehearsal with a generated throwaway key, and the certificate must differ from the release one |

So a tag can never publish a debug-signed artifact, and a missing secret is
reported as a missing secret rather than discovered as an install failure. If you
have not set the secrets yet, do not tag: push the commit, run the workflow by
hand (`gh workflow run release.yml --ref main`) and read the rehearsal log.

To do it by hand instead:

```bash
./gradlew assembleRelease
gh release create v<version> \
  app/build/outputs/apk/release/app-release.apk \
  --title "Memory Map v<version>" \
  --notes-file CHANGELOG.md
```

## 4b) The map tiles a release will use

The default tile host is the public OpenStreetMap server, which is fine for a development
build and **not** fine for a distributed app without prior permission from the OSM
Foundation (docs/SERVICE_LIMITS.md §2). `release.yml` refuses a `v*` tag while that is
still the case, so a release cannot go out by accident:

```bash
python3 ci/check-tile-provider.py                      # is the source consistent?
python3 ci/check-tile-provider.py \
  --ref "$GITHUB_REF" --tile-server "$MAP_TILE_SERVER" # what would the policy decide?
```

Pick a provider (the options and their limits are in §2.2 of that document) and set:

| Where | Name | Value |
|---|---|---|
| repository **variable** | `MEMORYMAP_TILE_SERVER` | the template, e.g. `https://api.maptiler.com/maps/basic-v2/{z}/{x}/{y}.png?key={key}` |
| repository **variable** | `MEMORYMAP_TILE_ATTRIBUTION` | the credit that host requires |
| repository **secret** | `MEMORYMAP_TILE_KEY` | the key, when the host wants one |
| repository **variable** | `MEMORYMAP_OSM_PERMITTED` | `true` **only** if permission was granted for the public OSM tiles |
| `local.properties` (never committed) | `MAP_TILE_SERVER`, `MAP_ATTRIBUTION`, `MAP_TILE_KEY`, `MAP_MAX_ZOOM` | the same values for a local build |

For a local build the names have no `MEMORYMAP_` prefix because they are build inputs, not
CI secrets to publish. The key is never committed: `ci/check-keystore-leaks.py` reads the
index for key-shaped files, and `local.properties` is ignored.

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
  `featureGraphic.png` (1024x500) are generated by `tools/make_store_images.py`,
  which draws them from the app's own palette: the icon is the launcher design
  rasterised, so the store and the launcher show the same mark, and the feature
  graphic deliberately carries no text because Arabic needs a shaping engine and a
  font. Re-run the script after changing `values/colors.xml` or the launcher
  drawable; `ci/check-store-metadata.py` fails the build if the files are missing
  or are not exactly those sizes.

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
- Walk `docs/MANUAL_QA.md` on a real phone before uploading. Nothing on a runner
  has touched a camera, a GPS fix or a real Supabase project, and the checklist is
  the honest list of what that leaves unproven.

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
