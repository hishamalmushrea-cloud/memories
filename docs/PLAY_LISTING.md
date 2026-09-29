# Google Play — what the store requires, what this repository already answers, and what only the owner can do

Read on 2026-09-28. The store's own pages are the authority; this file records which of
their requirements the repository satisfies **and how that was checked**, so that the
remaining list is short and honest.

**Nothing is published, and no release has been uploaded.** The app has never been on the
Play Console, and this file does not claim otherwise — the specification is explicit
(`MemoryMap_Full_Prompt.md` §54: do not present the app as published before it is).

## 1) What the repository already answers

| Requirement (Play) | State here | Evidence |
|---|---|---|
| Upload format: an Android App Bundle, signed | built and signed on every run | `bundleRelease` in both workflows; the AAB carries the release certificate (last green run: 9,997,225 bytes, `F76390B1…`) |
| `targetSdk` at the current requirement (API 36 since 31 Aug 2026) | 36 | `object Sdk` in `app/build.gradle.kts`; the reasoning and the 37 decision are in `docs/PLATFORM_UPGRADE.md` |
| Play App Signing | the keystore in this project is the **upload** key | `docs/RELEASE.md` §1–§4; the fingerprint the artifact must carry is `ci/release-fingerprint.txt` |
| Title 30 / short description 80 / full description 4000 characters | inside every limit, in both languages | `ci/check-store-metadata.py` over `fastlane/metadata/android/{ar,en-US}/` |
| Icon 512×512 PNG | present and exactly 512×512 | same guard, which reads the PNG header itself |
| Feature graphic 1024×500 | present, exactly 1024×500 | same guard |
| Release notes per version code | present, named after `versionCode` | `fastlane/metadata/android/*/changelogs/1.txt`, checked by the same guard |
| Privacy policy **inside the app** | readable in both languages from the account screen | `ui/profile/PrivacyScreen.kt` + `assets/privacy_{ar,en}.md`; `ci/check-docs.py` fails if the shipped copy and `docs/legal/PRIVACY_*.md` differ |
| Privacy policy **in the listing** | the text exists; the URL does not | `docs/legal/PRIVACY_{AR,EN}.md` — hosting it is item 2 below |
| In-app account deletion | it exists, and it deletes rather than deactivates | `AuthRepository.deleteAccount()` + the confirmation on the account screen; the server-side deletion is a transaction, so a failure changes nothing |
| No ads, no analytics, no trackers | none, by construction | no advertising or analytics dependency in `gradle/libs.versions.toml`; `ci/check-security.sh` |
| Data safety: collected data is encrypted in transit | yes | one HTTPS client; no cleartext exception anywhere in the manifest |
| Native library compatibility (64-bit, 16 KB pages) | the app **does** ship native libraries — twelve of them, `lib/arm64-v8a`, `libarmeabi-v7a` and the rest, from Compose (`libandroidx.graphics.path.so`) and CameraX (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`). Reading the build file said "no NDK, so nothing to check"; reading the artifact said otherwise | `ci/check-artifacts.py` runs `zipalign -c -P 16 -v 4` on the debug APK, the release APK and the bundle, fails when a library is misaligned, and prints the tool's verdict in the run report. The first version of this check only listed what it found and failed on it, which is how the wrong assumption was found |
| Permissions kept to the minimum | seven, no background location, no storage | manifest; listed in `docs/MANUAL_QA_RESULTS.md` §1 |
| Reviewer access to a gated app | the app is usable with **no account at all** ("continue without an account"), so a reviewer can reach every local screen; the cloud half needs a demo account | `docs/MANUAL_QA_RESULTS.md`; providing the credentials is item 2 below |

## 2) What only the owner can do

These are Play Console actions and public hosting: nothing in the repository can perform
them, and none of them is done.

1. **Developer account.** $25 one-time fee, identity verification, and — for new personal
   accounts — device verification through the Play Console app on a physical, non-rooted
   device running Android 10 or later. A personal account created after 13 November 2023
   must also run a **closed test with at least 12 testers for 14 continuous days** before
   production access is granted, so that clock starts before the publish day, not on it.
2. **Host the privacy policy at a public HTTPS URL**, and put that URL in the Play Console's
   designated field. GitHub Pages on this repository is enough; the page must be scoped to
   the app and must load without error.
3. **Provide an account-deletion web URL.** The store requires deletion to be reachable
   *outside* the app as well as inside it, and "freezing" an account does not count. The
   in-app route is done; the web route is a page that says how to request deletion (a form,
   or an address, is enough). This is the one requirement in this file that the repository
   has no way to satisfy on its own, and it is a hard blocker for a release with accounts.
4. **Complete the App content declarations:** the Data safety form (see §3), the content
   rating questionnaire (IARC), target audience, ads declaration (none), and the health and
   financial declarations, which must be answered even when the answer is "no features".
5. **Give the review team access instructions.** The app opens without an account, so the
   instructions are one sentence: "continue without an account reaches every local screen;
   the cloud features need the demo account below", with the demo account's email and
   password.
6. **Take the screenshots** (§4) from a real build on a real device.

## 3) The Data safety answers this app implies

Filled in from the code, not from habit. The store cross-checks this form against the
binary, and a mismatch is a rejection reason in its own right.

| Question | This app |
|---|---|
| Does the app collect or share user data? | It collects data **only for users who connect a Supabase project**: the account email, and whatever they choose to sync — memories, diary entries, people, places, media. With no project configured, nothing leaves the device. |
| Data types to declare | Email address; user IDs; photos and videos; audio files; approximate and precise location (only when the user attaches one to a record); app activity (the records themselves). Nothing else. |
| Is it shared with third parties? | No. The only recipient is the Supabase project **the user configured**, which is the developer's own backend — not a third party, not an ad network, not an analytics service. |
| Encrypted in transit? | Yes: HTTPS only. |
| Can users request deletion? | Yes, in the app (deletes the records, the profile row and the auth user in one transaction) — and the web route is item 2.3 above. |
| Ads or tracking? | None, and this is verifiable in the dependency list. |

## 4) The screenshot plan

Play needs **at least two** phone screenshots, eight at most, each 1080×1920 or larger,
16:9 or 9:16, PNG or JPEG, and the listing's first two are the ones a browser actually
sees. They must come from the real build — the store forbids mockups that show a product
different from the shipped one, and a screenshot that shows data the app cannot hold would
be a claim the app does not make.

The shots are ordered as the store shows them: the first two say what the app is.

| # | Screen | What must be visible, and why it is in this order |
|---|---|---|
| 1 | The day view with a diary note and two events | the app's centre: one day, its events, its note. Arabic UI. |
| 2 | A memory with a photo, a place and a person | the second half of the promise: a memory is geo-linked and linked to people |
| 3 | The map with several markers over a real city | that the map is a map, with the attribution line visible |
| 4 | The timeline, a few years on screen | the life-timeline idea, which no other screenshot shows |
| 5 | The week/month grid with counts | that the app has been thought through beyond a list |
| 6 | Search with a parsed query and its results | the search is local and structured |
| 7 | The backup screen mid-export | that the archive is the user's own files |
| 8 | The privacy policy screen | that the promises are readable in the app |

Rules that matter when taking them: no personal data from a real life (use the demo
account, with content that is obviously illustrative), system status bars clean, and the
same device for all eight so the listing looks like one product. The Arabic listing gets
the Arabic shots and the English listing the English ones; the interface language is a
setting, so both sets come from the same build.

## 5) What this file is not

It is not a submission, and it is not a claim that the app is ready to be submitted. Items
1–6 of §2 are open, the manual QA list in `docs/MANUAL_QA_RESULTS.md` §2–§3 is open, and the
Supabase project does not exist yet. When those are done, this file is the checklist to
walk — and the answer to "why is this box ticked" is the evidence column, not a memory.
