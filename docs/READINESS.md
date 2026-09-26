# Getting to a released app — the plan, and what is already done

This is the plan for finishing the project: what is left, in what order, why each
step matters, and how it will be verified. It exists because "ready" is a claim,
and a claim needs a list underneath it.

The app is not published anywhere. Nothing below changes that until the last
stage, and the last stage cannot be done from this repository alone.

## Where it stands

| | |
|---|---|
| Build | `assembleDebug`, `assembleRelease` and `bundleRelease` all pass in CI; debug APK ≈ 28.9 MB, release APK ≈ 4.5 MB, AAB ≈ 9.9 MB |
| Tests | 431 unit tests, 0 failures, 0 ignored, 0 errors |
| Lint | 0 errors, 37 warnings — every one of them version advice (`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`), which is stage 5 |
| Guards | ten checks in `ci/` run before Gradle, plus `verify-dependencies` and `check-security` |
| Database | `supabase/schema.sql` applies twice and passes 24 checks on a real PostgreSQL (stage 1) |
| Store | Metadata text is inside the limits for `ar` and `en-US`; the images do not exist yet |
| Published | nowhere |

## Stage 1 — the Supabase schema, executed  ✅ done

**Why.** It was the largest unverified thing in the repository: 623 lines of SQL
that no build step reads, whose only reader was a test that treats it as text. A
migration can be read and look right while failing on its first statement.

**What.** `ci/check-schema.py` starts a throwaway PostgreSQL, creates the shapes
the migration assumes (`supabase/verify/00_supabase_stubs.sql`), applies
`supabase/schema.sql` twice from its real path, and runs
`supabase/verify/10_checks.sql` — 22 checks performed as `authenticated` and as
`anon`, never as the owner of the tables.

**What it found, and fixed.**

1. The migration could not be applied twice: types, tables, indexes, policies and
   the six `updated_at` triggers all failed on a second run.
2. `anon` could execute both deletion functions, because Supabase grants execute
   on new functions to `anon` through its default privileges and the schema only
   revoked from `public`.

**Verified by.** Six mutations of the schema and of the client's constants, each
of which the checker catches with the guarantee named: a policy that stops
checking the owner, a synced table that loses its stamp trigger, a migration that
stops being re-runnable, an `anon` role left able to delete, a deletion function
that stops being `security definer`, and a renamed RPC.

**Still not covered.** GoTrue, PostgREST and the storage service are not part of
this. The first run against a real project is still a first run.

## Stage 2 — the code-level lint warnings  ✅ done

**Why.** Of the 51 warnings, 35 were "a newer version exists", which is
information rather than a defect. The rest were things a reader of the code should
not have to wonder about, and one was a real gap: nothing was declared about
backup extraction on Android 12 and later, where the platform reads
`android:dataExtractionRules` rather than only `android:allowBackup`.

| Rule | Count | What it meant here | What changed |
|---|---|---|---|
| `PluralsCandidate` | 7 | a count followed by a noun, in a plain string | seven `<plurals>` in both locales, six Arabic quantities each, and `pluralStringResource` at the call sites |
| `UseKtx` | 3 | `Uri.parse` and two `Uri.fromFile` calls in the backup writer | the `androidx.core.net` extensions (`toUri`) |
| `UnusedResources` | 2 | two colours left over from a theme now built in Kotlin | removed |
| `DataExtractionRules` | 1 | no `android:dataExtractionRules`, which is what API 31+ reads | a rules file excluding every domain, for backup and for transfer |
| `ObsoleteSdkInt` | 1 | `mipmap-anydpi-v26` at `minSdk = 26` | the directory is `mipmap-anydpi` |
| `AndroidGradlePluginVersion` | 2 | the wrapper and AGP are behind | moved to stage 5 with the rest of the version work |

**Verified by.** `lintDebug` warnings by rule in the run's report issue, and the
unit tests still at 431 passing with nothing skipped.

**Deliberately not touched.** `Icons.Outlined.Article` in `QuickAddSheet.kt` is
deprecated, and it is a *compiler* warning rather than a lint one. The obvious
rename does not work - the auto-mirrored package in the core artifact carries
seven icons and `Article` is not one of them - and the library it comes from,
`material-icons-extended`, is itself deprecated in favour of Material Symbols
assets. Silencing one import would leave the dependency in place. The honest
change is to move the handful of icons the app uses to vector assets, which is a
change with a diff to review and screenshots to check, not a rename.

## Stage 3 — a guard for duplicate imports  ✅ done

**Why.** `TestDoubles.kt` imported `com.memorymap.domain.model.User` twice and
`ci/check-import-order.py` — which reads every import in 176 files — did not
notice. It was found by hand. Nothing else looks at this: the Kotlin compiler
warns about an unused import, not a repeated one, and lint does not look either.

**What.** The guard collects every import as it walks a file, normalises the
whitespace, and fails on a repeated one with both line numbers. It also fails if it
reads fewer than 2500 imports, because a checker that examines nothing passes.

**Verified by.** Five mutations: the exact `TestDoubles.kt` duplicate; the same
duplicate written with different spacing; an import spliced in after a declaration
(caught at the line); an aliased import beside the plain one (deliberately not
reported — a second name for the same type is a different import); and a scanner
pointed at an empty directory, which fails on the floor instead of reporting OK.

## Stage 4 — time that survives two time zones  ✅ done

**Why.** Every stamp the app wrote was a clock reading with no offset, and the
conflict resolver read both sides in whichever zone the phone was in *at the
moment of comparison*. A memory edited at 10:00 in Sanaa and then again at 09:00
in London after a flight had its newer edit judged older by an hour, so the edit
was discarded in favour of the server's older copy - silently, once, and
unrecoverably.

**What.** `util/SyncTime.kt` is now the only place that says what a stamp is.
Writers stamp `Instant.now()`. What is stored is the moment itself, so nothing is
converted on the way to the server or on the way back. The conflict resolver and
the watermark fold read through `SyncTime.instant`, so there is one reading of
what a stamp means instead of two. `LocalDateTime.iso()` was removed from the
mappers, so the naive writer no longer exists to be used again.

**Deliberately unchanged.** `memory_date`, `entry_date` and `note_date` are days,
not moments. The wall-clock time of a diary event is what the user chose, stored
as the moment they chose it in. Room's column types did not change, so there is
no schema migration: rows written before this change hold plain local text and
are read in the device's zone - written down in the code, and asserted by a test
named after it.

**Verified by.** Four new tests, each of which fails if the old behaviour comes
back: `an edit made after a flight is still the later one` (switches the JVM's
default zone between the two writes), `a stamp that already carries its offset is
left as it is`, `what is written to the database is a moment, not a clock reading`
(reads the row back out of Room), and `a remote stamp keeps the instant the server
sent`. Three existing assertions that encoded the old design were rewritten, each
with the reason in the test.
## Stage 5 — dependency refresh, one group at a time

**Why.** 35 advisories are pinned versions that have moved on: AGP, Kotlin, KSP,
Compose BOM, Room, Ktor, Coil, CameraX. Upgrading them all at once and pushing
once would turn a green build into a question about which one broke it.

**What.** One group per commit, `verify-dependencies.sh` first, then the full CI
run before the next group. Anything that needs a coordinated bump (Kotlin + KSP +
Compose compiler) moves together.

**Verified by.** A green CI run per group, with the lint count in the same report.

## Stage 6 — the round trips a user actually does

**Why.** The unit tests cover units. Nobody has yet proved that a backup written
on one device restores on another, that a deleted memory stays deleted after a
sync (the tombstone path), or that a photo and its row stay consistent when the
upload succeeds and the row write fails.

**What.** Robolectric tests for the backup → new database → restore round trip,
the delete-then-sync-then-download path, and the media ordering (upload, then
row), each against the real Room database and fakes at the network boundary.

**Verified by.** Tests that fail if the ordering is reversed — checked by
reversing it.

## Stage 7 — release mechanics

**Why.** `docs/RELEASE.md` describes the flow; nothing has executed it. A release
that fails at the last step is the worst moment to discover it.

**What.**

- Merge the open pull request into `main`.
- `git tag v0.1.0` and push the tag; `release.yml` builds, signs and attaches.
- The four secrets (`MEMORYMAP_KEYSTORE_*`, `MEMORYMAP_KEY_*`) or the build falls
  back to debug signing with a visible warning — which is not a release build.
- Store assets: 512×512 icon, 1024×500 feature graphic, at least two screenshots.
  The text is already written and inside the limits; the images cannot be produced
  in a sandbox with no device and no emulator, and the feature graphic is not a
  screenshot of an app that has never run on a screen.

**Verified by.** A tag whose workflow run is green and whose release carries a
signed APK built from the tag.

## Stage 8 — what only a device and a project can prove

**Why.** Everything above runs on a runner. A camera, a GPS fix, a real Supabase
project, a real map tile server and the Play upload are not on a runner.

**What.** A checklist in `docs/MANUAL_QA.md`, one line per claim the app makes,
with the expected observation: first run without a project, sign-up and sign-in,
sync between two devices, offline edit then reconnect, the nearby filter with a
real location, camera capture and video, backup to SD card and restore, account
deletion, RTL layout, dark theme, Arabic and English.

**Verified by.** Someone going through it, and the list being the honest answer to
"how do you know it works?".

## Order

Stages 2–6 are code and can be done one commit at a time. Stage 7 needs the pull
request merged, and stage 8 needs a phone. The only hard dependency between them
is that stage 7 should follow 2–5, so that what is tagged is the cleaned-up
version.
