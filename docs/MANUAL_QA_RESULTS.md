# Manual QA — what could be checked from here, and what cannot

`docs/MANUAL_QA.md` is the list of claims that only a phone and a real project can
prove. This file is the other half: the part of that list that **was** checked, the
evidence for each, and the exact list that is waiting for hardware.

Nothing in this file is a claim that the app has been used. It has not been used. What
follows separates three things that are easy to blur together:

1. **Checked from this repository** — the claim is a property of the code or the
   resources, so a command settles it. Each row names the command and the result.
2. **Waiting on a phone** — a camera, a microphone, a GPS chip, a screen reader or a
   locked screen is required.
3. **Waiting on a Supabase project** — two accounts and a real server are required.

## 1) Checked from here, with the evidence

Run on 2026-09-28 against `e89276c`.

| Manual QA claim | How it was settled | Result |
|---|---|---|
| "No background location" | `grep -o 'android:name="[^"]*"' app/src/main/AndroidManifest.xml` | The manifest declares **seven** permissions and no more: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`. `ACCESS_BACKGROUND_LOCATION` is absent. |
| "Nothing asks for storage access" | same command | no `READ_EXTERNAL_STORAGE`, no `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, and no `READ_MEDIA_*` |
| "No location request while the app is in the background" | `grep -rn "getCurrentLocation\|LocationManager\|FusedLocation" app/src/main/java/` | the only reader is `util/LocationReader.kt`, a one-shot: it registers a listener, removes it on the first fix, on cancellation and on timeout (`DEFAULT_TIMEOUT_MS = 12_000`), and keeps nothing alive. There is no `Service` in the manifest, so there is nothing that could collect a position with the app closed. |
| "The app works with no network at all" (offline-first) | `ci/check-suspend-calls.py`, `ci/check-queries.py` plus the 537 unit tests, which run with Room on the JVM and fakes at the network boundary | no test or repository call requires a socket; the Supabase client is only reachable through the sync and auth repositories |
| "No debug logging from this app in a release build" | `app/proguard-rules.pro` | `-assumenosideeffects class com.memorymap.util.MmLog { public static void d(...); public static void v(...); }` — the R8 rules strip the two debug levels from a release build; `w()` and `e()` stay and take an exception, never user content |
| "Every screen is translated; nothing is half-Arabic" | python over both locale files: compare the key sets | `values/strings.xml` and `values-en/strings.xml` hold **274 strings and 20 plurals each**, and the key sets are *equal*: nothing exists in one language only. `ci/check-strings.py` additionally proves placeholders match and rejects the bare apostrophe that would break an Arabic string. |
| "The archive is readable text on a computer" | `app/src/main/java/com/memorymap/di/RepositoryModule.kt` | the injected `Json` uses `prettyPrint = true`, `encodeDefaults = true`, `ignoreUnknownKeys = true`; `BackupArchive` writes `manifest.json` plus one document per record type through it |
| "The map carries an attribution line" | `TileServerMapProvider` / `MapProviders` and `ci/check-tile-provider.py` | the credit is a build setting with the OpenStreetMap default, is displayed by the map screen, and a release whose credit is blank or whose host is the public OSM server is refused before it builds |
| "It is signed with the release key, not the debug one" | `ci/release-fingerprint.txt` + the last green run's `signing.log` | both release artifacts carry the release certificate (`F76390B1…`); the debug APK carries the debug one, and the two differ |
| "No key or token is visible in the UI or in logcat" | `ci/check-keystore-leaks.py`, `ci/check-security.sh`, and `MmLog`'s contract | no key-shaped file is tracked; the signing key only ever exists in the runner's temporary directory, and the log rules exclude payloads by construction |

Two of those rows replace a step that `docs/MANUAL_QA.md` asked a person to walk: the
permission lines and the translation coverage are now settled by the manifest and the
resource files, which is stronger evidence than a look at the settings screen.

## 2) Waiting on a phone

Ordered so that one phone session covers everything. Every line needs a device with a
camera, a microphone and GPS, on Android 8.0 (API 26) or newer. **None of it has been
done.**

### A. The first twenty minutes (install and look)

- [ ] Install the debug APK and open it with no `local.properties` values: it opens, no
      crash, no login wall, five bottom destinations with Arabic labels.
- [ ] Settings → about/privacy: readable in the app's language.
- [ ] Airplane mode on: writing a memory, the diary and the timeline all still work.
- [ ] English then Arabic: the layout flips right-to-left, the back arrow points the right
      way, numbers still read left-to-right, nothing is cut off.
- [ ] Font size at 200%: nothing is cut off; every button is still reachable.
- [ ] Dark theme: no screen keeps a light background.
- [ ] TalkBack, walking the five tabs: every tab, button and field is announced with a
      name, not as "button".

### B. The camera, the microphone and the two pickers

- [ ] Take a photo in the app: saved, attached, and the original in the gallery is intact.
- [ ] Record a video: it records, stops, and plays back from the memory.
- [ ] Record audio: it records, stops, and plays back from the memory.
- [ ] Press Home **while audio is playing**, and again **while recording**: note what
      happens. This is the behaviour `docs/PLATFORM_UPGRADE.md` identifies as the one item
      that would need work before a `targetSdk 37` move, so the observation is wanted
      either way.
- [ ] Deny the camera permission, then try to capture: the app explains what it needs and
      does not crash.
- [ ] Deny the location permission, then try to pick a place: it says so and offers manual
      map picking.
- [ ] Turn GPS off, then press "I am here": it gives up after about twelve seconds instead
      of hanging.
- [ ] Attach a video larger than 50 MB to a memory and switch its cloud button on: a
      message names the limit in megabytes, and the profile screen's pending count does
      not move.
- [ ] The same video with the cloud button off: nothing is said.
- [ ] A large phone photo that is under 50 MB after preparation: accepted, and it arrives
      in the bucket.
- [ ] Delete a photo's file with a file manager, then open the memory: it opens and says
      the attachment is missing rather than crashing.

### C. The core round trip

- [ ] Create a memory with a title, text, a place from the map, a photo and a person:
      reopening the editor shows all of it, and rotating the phone keeps all of it.
- [ ] Open the map tab: the memory is a marker where you picked it, the map is not grey,
      and the attribution line is visible.
- [ ] Write a diary event with a time: it appears under that day in time order; the week,
      month and year views each count what the level below shows.
- [ ] The timeline: newest first, with dates in the device's own time zone.
- [ ] Set the phone's clock and time zone to something else, reopen: times still read as
      the moment they happened, in the new zone.

### D. Backup by hand

- [ ] Export to a folder, connect the phone to a computer: `manifest.json` and the JSON
      documents are readable text and the photos are in `media/`.
- [ ] Wipe the app's data, reinstall, sign in as the same account, import: everything
      returns with its links and attachments.
- [ ] Import the same archive twice: the second run reports that nothing was new.
- [ ] Delete a memory, then import an older archive that still contains it: it does not
      come back.
- [ ] Restore an archive while signed in as a *different* account: note what happens. The
      rows are restored under the archive's account and are invisible to the current one;
      nothing leaks and nothing warns. This is the known limitation in `docs/READINESS.md`.

### E. The release build on the phone

- [ ] Install the release APK: it starts and behaves as the debug build does.
- [ ] `bash ci/apk-signer-fingerprint.sh <apk>` equals `ci/release-fingerprint.txt`, and
      installing it over an earlier build signed with the same key replaces the app
      instead of refusing.
- [ ] Install a build signed with a different key over it: Android refuses with a
      signature mismatch. That refusal is what makes the previous line meaningful.
- [ ] Watch logcat for the app's tag in the release build: no debug output.

## 3) Waiting on a Supabase project

These need a real project with `supabase/schema.sql` applied, two accounts, and either two
devices or one device and one emulator. **None of it has been done, and the project itself
is not configured yet** — that is item 1 of the current work, and the same session will
answer both.

- [ ] Sign in on device A: it succeeds, and no key or token appears in the UI or in logcat.
- [ ] Sync A: the status chip stops saying pending and the run reports what it sent.
- [ ] Sign in as the same account on device B and sync: the records arrive, including the
      links between them.
- [ ] Edit on B, sync B, sync A: A shows B's edit.
- [ ] Delete on A while offline, sync A: it does not come back on A, and B does not show it
      after syncing.
- [ ] Airplane mode on A, edit, close and reopen the app: the record is still there and
      still marked pending.
- [ ] Network back on, sync: the pending edits go up and the markers clear.
- [ ] Move the phone's clock forward a day, edit, move it back, sync: nothing is lost and
      nothing is duplicated.
- [ ] Sign out on A: the account's local data is no longer shown.
- [ ] Delete the account in the app: it asks first, names what will be deleted, and the
      local and cloud copies are gone afterwards.
- [ ] With the second account, confirm the first account's rows are invisible — that is the
      row-level security working, and it is the check that matters most on a real project.

## What this file is not

It is not a claim that the manual list has been walked. Sections 2 and 3 are open, and
until they are ticked the honest answer to "how do you know it works?" stays what
`docs/MANUAL_QA.md` already says: the unit suite passes, and the manual list is written
down and waiting.
