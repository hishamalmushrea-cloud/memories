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
