# Raising the platform pin — the open decision, and exactly what it would take

**Status: not applied, and nothing in this document has been run.** The specification
fixes `compileSdk = 36` and `targetSdk = 36` (`MemoryMap_Full_Prompt.md`), and the
project has kept that pin through every dependency group it could move without
breaking it. This file exists so the decision can be made by reading rather than by
experimenting on the build, and so that whoever makes it knows which run would catch
which mistake.

The short version: **nothing forces the move today.** Google Play requires new apps and
updates to target Android 16 (API level 36) since 31 August 2026, and this project
already targets 36, so it is compliant and stays discoverable. What the move would buy
is dependency freshness: the 25 advisories recorded in `docs/READINESS.md` (stage 5)
are all the *newest* releases, and the newest releases now require a newer platform.

## What is pinned today

| Where | What | Value |
|---|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle | 8.14.3 |
| `gradle/libs.versions.toml` | AGP | 8.11.1 |
| `gradle/libs.versions.toml` | Kotlin / KSP / Hilt plugin | 2.2.0 / 2.2.0-2.0.2 / 2.58 |
| `app/build.gradle.kts` (`object Sdk`) | compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| `app/build.gradle.kts` | Java / JVM target | 17 |
| `gradle/libs.versions.toml` | Compose BOM, Room, supabase-kt, Ktor | 2025.06.01, 2.8.5, 3.1.1, 3.1.1 |

## The wall, as the runs proved it

Not from release notes: each line below is what CI said when the version was tried.

| Library | Tried | What the run said |
|---|---|---|
| `androidx.core:core-ktx` | 1.19.1 | fails in `checkDebugAarMetadata`: compile against API 37 |
| `androidx.appcompat:appcompat` | 1.8.0 | same |
| `androidx.core:core-splashscreen` | 1.2.0 | same |
| `androidx.work:work-runtime-ktx` | 2.12.0 | same |
| Hilt | 2.59.2 and 2.60.1 | *"The Hilt Android Gradle plugin is only compatible with Android Gradle plugin (AGP) version 9.0.0 or higher (found Android Gradle Plugin version 8.11.1)"* |
| Ktor 3.6.0 + supabase-kt 3.8.0 | — | `com.squareup.okhttp3:okhttp-android:5.5.0` requires compiling against API 37; forcing a transitive downgrade would be a runtime `NoSuchMethodError` no test here would see |

`androidx.hilt:1.2.0` is deliberately *not* on this list: it carries Hilt at runtime,
and a runtime newer than the compiler that generated the bindings is a mismatch with no
benefit until Hilt itself moves.

## What the move would cost — versions, not opinions

- **AGP 9.1.0** requires Gradle **9.1.0 or newer** (the AGP 8.x line can run on Gradle 9
  but AGP 9 cannot run on Gradle 8), JDK 17 (already the project's), and **build-tools
  36.0.0**; Kotlin Gradle Plugin 2.0+ (2.2.10 is bundled with AGP 9.1). So the wrapper
  moves first, and AGP cannot be moved alone.
- **Hilt 2.60** is the first Hilt that supports AGP 9, and it requires Gradle 9.1+ too.
  2.59 was held back deliberately upstream ("because it forces users onto AGP 9").
- **KSP** is versioned against Kotlin. The Kotlin-tagged line stops at `2.2.21-2.0.5`
  and standalone `2.3.x` exists after it, so KSP must be chosen against the exact
  Kotlin that ends up pinned — it cannot be left where it is.
- **Kotlin** above 2.2 changes the Compose compiler with it (the Compose compiler plugin
  is versioned by Kotlin, not by the Compose BOM), so the BOM is re-checked rather than
  assumed.
- `compileSdk`/`targetSdk` 37 in `object Sdk`, which is the one line the specification
  currently fixes.

## What could break, and where it would be caught

| Risk | Caught by |
|---|---|
| AGP 9 lint rules and its R8 defaults differing from 8.11.1's | `ci/…` → `lintDebug` in the workflow; 0 errors is a gate, and the release APK is built in the same run |
| A Room/KSP mismatch producing different generated code | the committed schema for version 4 is diffed by the *"Commit the generated Room schema if it is tracked"* step, and `check-schema.py` runs the SQL guarantees |
| A Kotlin/Compose compiler bump changing semantics in Compose code | the 461 unit tests, then `docs/MANUAL_QA.md` on a device — tests do not see recomposition |
| WorkManager or foreground-service behaviour under a new target | `WorkManagerTest`-style coverage plus the manual list (`SERVICE_LIMITS.md` §2 for the network side) |
| Supabase/Ktor moving to OkHttp 5 | the sync tests and the two-device manual run; this is the one change with a real runtime failure mode and no test that would see it, which is why the group was reverted rather than forced |
| Gradle 9 changing configuration behaviour for the two workflows | `ci/check-workflows.py` (the SDK step must stay byte-identical) and a green build run |

## If the decision is yes, in this order

One group per commit, each one green before the next — the same discipline stage 5
used, because a coordinated upgrade pushed once turns a green build into a debate about
which of six changes broke it:

1. Gradle wrapper 8.14.3 → 9.1.0 or newer. Nothing else. Build, test, lint.
2. AGP, Kotlin and KSP together (they cannot move apart), `compileSdk`/`targetSdk` 37,
   Compose BOM re-checked against the new Kotlin. Build, test, lint, both APKs.
3. Hilt plugin and runtime 2.58 → 2.60.1, `androidx.hilt` only if the compiler moved too.
4. The four AndroidX libraries that were waiting (`core-ktx`, `appcompat`,
   `splashscreen`, `work`).
5. Ktor and supabase-kt last, because that group carries the runtime risk.

If any group fails in a way that is not a one-line fix, revert that group's commit and
stop: the pin is a specification decision, and every group above is optional freshness.

## What the specification would need changed

`MemoryMap_Full_Prompt.md` fixes `compileSdk = 36` and `targetSdk = 36`. Moving to 37 is
therefore a change to the specification, not only to the build, and this project's rule
is that a specification change is recorded rather than absorbed quietly. If the answer
is "stay at 36", the honest thing to record is that 25 dependency advisories stay open
on purpose, with this file as the reason — which is what `docs/READINESS.md` already
says.

## Sources for the store side

The Play requirement was read on **2026-09-27** from public coverage of Google Play's
target API level policy: new apps and updates must target Android 16 (API level 36)
from 31 August 2026, an app that is left targeting below Android 15 (API level 35)
stops being offered to new users on newer devices, and the cycle repeats with each
Android release. This document deliberately does **not** name a date for API 37: the
next cycle has not been announced, and a guessed deadline in a decision document is
worse than none.


## The examination of 2026-09-28: every API-37 behaviour change against this code

This is the answer to "should the pin move to 37 now", written so it can be checked rather
than believed. Sources read on 2026-09-28: the Android 17 behaviour-change list for apps
targeting API 37 (developer.android.com, page last updated 2026-09-16) and the Google Play
target-API requirement page. **No version was changed by this examination.**

### What the store requires, today

| Fact | Value | Consequence here |
|---|---|---|
| Android 17 (API 37) released | 16 June 2026 - Google moved the yearly release to Q2 | a 37 platform exists, so this is a real choice and not a future one |
| Play requires new apps and updates to target | **API 36, since 31 August 2026**, extension to 1 November 2026 requestable | `targetSdk 36` is compliant and stays discoverable |
| Play requires API 37 from | no dated requirement on the requirement page; a Google engineer stated August 2027 in a developer blog post | roughly a year of runway, and no policy text to plan a date against |

So the deadline question has one answer: **nothing forces the move today.** What would
justify it is dependency freshness, which is the next section.

### Every behaviour that fires at targetSdk 37, checked against this repository

Each row is the change as the platform documents it, then what this project contains. The
"what this project contains" column is a grep result, not a recollection.

| Change at targetSdk 37 | What this project contains | Verdict |
|---|---|---|
| Memory limit widget | no widget of any kind; the app is one activity and Compose screens | unaffected |
| Lock-free `MessageQueue` | a platform-internal change; nothing in this codebase touches it | unaffected, but worth one pass on a device |
| Static final fields unmodifiable | `grep -rn "getDeclaredField\|setAccessible\|java.lang.reflect" app/src/main/java` finds nothing | unaffected |
| Accessibility IME physical-keyboard support | no custom text-input or IME handling | unaffected |
| ECH (Encrypted Client Hello) enabled | one HTTPS client (Ktor/OkHttp) to the configured Supabase and tile hosts | needs a device test against the real project, not a code change |
| Local network permission required | `grep`: no `NsdManager`, no sockets, no `InetAddress`; the manifest declares `INTERNET` and `ACCESS_NETWORK_STATE` only | unaffected |
| Passwords hidden from physical devices | the field already uses a password transformation; the change alters what the *system keyboard* reveals, not the app | cosmetic, verify on a device with an external keyboard |
| OTP protection for standard SMS (three-hour delay) | no SMS code and no `READ_SMS`; authentication is email | unaffected |
| Activity Security (background-activity-launch hardening) | `grep`: no `PendingIntent`, no `IntentSender`, and no activity is ever started from the background | unaffected |
| Certificate transparency on by default | no `networkSecurityConfig`, no `TrustManager`, no certificate pinning; the app trusts the platform store, and the hosts it talks to are publicly trusted and CT-logged | unaffected |
| Safer native dynamic code loading | no NDK and no `System.load` (the specification forbids the NDK) | unaffected |
| Contacts Provider 2: PII columns and strict SQL | `READ_CONTACTS` is not declared and the contacts provider is never queried; "people" in this app are its own rows | unaffected |
| `setContentCaptureEnabled` deprecated | `grep`: the call appears nowhere | unaffected |
| **Background audio hardening** | `AudioPlayerController` holds an `android.media.MediaPlayer` and releases it from `DisposableEffect` - that is, when the composition leaves, **not** when the app goes to the background. There is no `Service` and no `FOREGROUND_SERVICE` permission in the manifest | **the one real work item** |
| Orientation, resizability and aspect-ratio constraints ignored on large screens | `grep` for `screenOrientation`, `resizeableActivity`, `minAspectRatio`: none declared | unaffected today, because there is no constraint to ignore |
| Bluetooth RFCOMM `read()` returns `-1` | no Bluetooth code | unaffected |

### The one work item, in full

At targetSdk 37 an app that interacts with audio while it is in the background must have a
foreground service with while-in-use capability (or hold the exact-alarm permission and use
`USAGE_ALARM`). The listener here keeps playing when the user presses Home, because nothing
in the composition is disposed. Today that is allowed; from 37 it is not.

There are two ways to fix it, and they are not equally large:

1. **Stop playback when the screen is no longer visible.** A lifecycle observer on the
   screen, or `Lifecycle.Event.ON_STOP` in the player's host, pausing the player. It fits
   what this app already promises - a diary that does nothing in the background - and it
   costs one small class plus a test.
2. **Move playback into a `MediaSessionService`.** Correct if the project ever wants
   playback to continue while the phone is locked. It is a new service, a media session, a
   notification, and the whole background-execution contract; it is a feature, not a
   migration step.

Option 1 is the one that matches the specification's "location only on demand; nothing
running in the background" line, and it is what a 37 move would need.

### What the toolchain would have to do, and in which order

The dependency side is already recorded in the table above this section: `core-ktx 1.19.x`,
`appcompat 1.8.0`, `work-runtime 2.12.0` and `okhttp-android 5.5.0` all fail
`checkDebugAarMetadata` at `compileSdk 36` with *"compile against API 37"*, and Hilt 2.59+
refuses AGP 8.x. So a 37 move is one ordered change, not a version bump:

1. Gradle wrapper 8.14.3 -> 9.1+ (AGP 9 cannot run on Gradle 8);
2. AGP 8.11.1 -> 9.1.0 (build-tools 36.0.0, JDK 17 - already the project's);
3. Hilt 2.58 -> 2.60.1 (the first Hilt that supports AGP 9);
4. Kotlin and KSP together (KSP is versioned against Kotlin), then the Compose BOM
   re-checked, because the Compose compiler plugin follows Kotlin, not the BOM;
5. `object Sdk` in `app/build.gradle.kts`: `COMPILE` and `TARGET` to 37;
6. the background-audio item above.

Steps 1-5 are mechanical and each one is visible in a run. Step 6 is the only one that is a
design decision.

### The recommendation, for the owner to accept or reject

**Keep `compileSdk 36` / `targetSdk 36` for `v0.1.0`.** It is compliant with the store's
requirement, every check in this repository passes on it, and nothing in the app is broken
by staying. The move buys newer libraries and a year of headroom, and it costs a toolchain
jump plus one behaviour item - work worth doing as its own change, with the dependency
upgrades and a full CI run attached to it, rather than folded into the first release.

If the owner wants it now, the order above is the plan, and step 6 is the one to do first,
because it is the only part that is a decision rather than a version.
