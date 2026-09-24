# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- People and places can now actually be linked to a record. The database layer
  and the repositories had accepted `personIds` and `placeIds` since Phase 7,
  but no editor ever passed them, so `كل الأحداث مع أحمد` could only ever return
  a person with zero records. Both the memory editor and the event editor now
  show every name the user has as a chip, toggle the link on tap, and create a
  person from a typed name — finding rather than creating, so one spelling of a
  name stays one person. Links are loaded on edit and replaced on save, which is
  what makes unlinking a name actually unlink it.
- Creating a *place* from an editor is deliberately narrower: a place needs
  coordinates, so the memory editor offers it only once the memory has a pin,
  and the event editor — which has no map picker — links to existing places
  rather than inventing one with no location.

### Added — Phase 10: Testing and Release

- A tag-triggered release workflow. Pushing `v1.0.0` runs the security gate, the
  unit tests and `lintRelease`, builds both an APK and an AAB, and creates the
  GitHub Release with the changelog as the notes. Signing comes from four
  repository secrets; without them the workflow still builds but marks the
  artifact debug-signed in bold, because a half-configured release should be
  obviously unusable rather than quietly distributed.
- Scale tests for the two hot paths §47 names. A twenty-year archive — 7,300
  events plus 3,650 memories — still builds a correct newest-first timeline,
  10,000 map pins cluster without losing a marker, and clustering work follows
  the pin count rather than their spread. The bounds are loose on purpose: a
  shared CI runner is slow and variable, and a flaky performance test teaches
  everybody to ignore the build. Exact timings are printed so a real slowdown is
  still visible inside the bound.
- `docs/RELEASE.md` now matches what exists: the security gate and the release
  build are pre-release requirements, and the tag flow and its secrets are
  documented.

- Account deletion, which the privacy policy already promised but no code path
  delivered. One action on the profile screen now removes every memory, event,
  diary note, person, place, attachment row and the media files themselves, plus
  the sync bookmark and the account row. It asks first, and afterwards reports
  how many records and files went rather than only that something did.
- The link tables cascade from their parent through foreign keys, but `media` has
  no foreign key, so `MediaDao.deleteForUser` reaches attachments through their
  owner. Without it the wipe would leave rows nothing can display.
- `MediaStore.clear` removes the bytes, which is the part a database cannot
  reach, and recreates the folders on demand so a wipe does not break the next
  photo.
- CI now assembles a **release** APK as well as a debug one. That is the only
  build that runs R8, so until now nothing had ever checked that the shrinker
  rules are valid or that minification keeps everything the app needs. The gate
  requires a release APK to exist, not merely for Gradle to exit zero.

### Fixed

- The privacy policy claimed the user could delete "your account together with
  its cloud data". The app cannot delete a Supabase account and never could.
  Both language versions now say plainly what the in-app deletion does — clears
  this device — and that cloud rows stay on the connected project's server until
  they are deleted there.

### Added — Phase 9: Security and Privacy

- A CI gate, `ci/check-security.sh`, that fails the build when a documented
  guarantee stops holding in the source: a `service_role` key in the client, a
  log call that bypasses `MmLog`, an OS backup switched back on, cleartext
  traffic, a background location permission, release minification turned off, or
  a log-stripping rule that no longer matches. Every check names the file and
  line it objects to. All nine were verified to fail on a deliberately broken
  tree, not just to pass on a clean one.
- Tests that hold the Row Level Security contract against the SQL that will be
  applied: every guarded table has a policy, the diary has no public or shared
  read path at all, a public memory requires an explicit `PUBLIC` and a live
  row, a shared one requires a grant, every write policy is owner-scoped, the
  media bucket is private and folder-scoped, and the owner can delete their own
  avatar. A policy dropped by a later edit now fails the build.
- Tests for the client-side guarantees: `allowBackup=false`, no cleartext, the
  exact permission set, release shrinking on, and the shape of the stripping
  rule.
- Privacy defaults are tested rather than assumed: a new memory is `PRIVATE`
  unless the user says otherwise, and `DailyEntry` has no visibility property at
  all, so §46's "never public by default" holds because the diary cannot be made
  public in the first place.
- `SupabaseConfig` now requires HTTPS. A project configured over plain HTTP is
  treated as not configured, so the app stays offline instead of putting the
  session token and the archive on the wire in the clear.
- An owner-delete policy for the `avatars` bucket, which had public read and
  owner write but no way to remove a file.
- The privacy policy (AR + EN) now states both new guarantees.

### Fixed

- `allowBackup` was `true`, and `data_extraction_rules.xml` listed the diary
  database and the whole media folder under `<cloud-backup>`. Android was
  therefore uploading the user's diary and every photo to a third party's
  servers, which nothing in the app or the policy disclosed and the user could
  not turn off from inside the app. Backup is now off and those rule files are
  gone; the export flow is how a copy gets made.
- The ProGuard rule that strips debug logs declared `public static void d(...)`.
  `MmLog` is a Kotlin `object`, so those are instance methods on the singleton
  and the rule matched nothing — the documented guarantee that `d()` and `v()`
  are stripped from release builds was not in fact true. The rule is now written
  without `static`, and a test keeps it that way.

### Added — Phase 8: Local Backup

- Export writes the whole archive to a folder the user picks, through the
  Storage Access Framework, so no storage permission is needed and the copy
  lands wherever the user wants it: Documents, an SD card, a synced directory.
  The layout is `manifest.json`, `memories.json`, `daily_entries.json`,
  `people.json`, `places.json`, `media.json` and `media/`.
- The archive holds the content of a record and none of this phone's
  bookkeeping. No sync status, no tombstones, no last-synced stamp: those
  describe one device's relationship with a server and would be meaningless on
  another phone. The point of the format is that getting your life back never
  depends on Supabase, or on this app, being reachable.
- Import merges rather than replaces, through the same rule sync uses. A newer
  local copy is kept, an older archived one is skipped, and a record deleted on
  this device stays deleted however recent the archive is — restoring a backup
  can neither destroy newer work nor resurrect a delete.
- Nothing is written until the manifest has been read back and shown: the
  archive's record counts and creation date appear in a confirmation dialog
  first, because merging somebody's life into an existing archive is a decision
  to make with the numbers in front of you.
- Attachments are copied as plain files named `<ownerId>_<mediaId>.<ext>` and
  re-linked to their record on the way back. An attachment whose owner is not
  on this device is left in the folder rather than written as an orphan.
- An archive written by a newer version still reads: unknown fields are ignored
  rather than rejecting the document, and a format version this app cannot
  understand is refused before anything is touched.
- Reached from the profile tab under "Backup and restore".

### Added — Phase 7: Search and Organisation

- One search over the whole local archive: memory titles and bodies, event
  titles and bodies, people, places and dates. Structured text search only —
  the specification rules out a model interpreting the query, so every result
  can be explained by pointing at the row that matched.
- `SearchQueryParser` reads a search box as structure. `مذكرات سبتمبر` is a
  month, `كل ما سجلته في صنعاء` is a place, `الأحداث مع أحمد` is a person,
  `15 مارس 2019` is a day. Arabic-Indic digits read the same as western ones,
  the Gregorian, Maghrebi, Levantine and English month names are all
  recognised, and stop words are dropped so `عن` does not match the archive.
- The screen says out loud what was understood — person, place, date, words —
  because structured search can only be trusted if the structure is visible.
- `DateConstraint` handles what a range cannot: a month with no year means that
  month in every year.
- People and places screens: add a name once, see how many records it is linked
  to, open it to everything connected, delete it. A place asks for a position as
  well as a name, because without coordinates it could not appear on the map.
- Words are AND-ed, so a longer query is narrower rather than noisier. Naming a
  person or place narrows by intersection; a bare name is offered as a way in
  rather than silently pulling in everything linked to it.
- Emotion filter on top of the words.
- Everything is scoped to the signed-in account and excludes tombstones, so a
  deleted record never resurfaces in search.
- Tests: `SearchQueryParserTest`, `DateConstraintTest`, `SearchRepositoryImplTest`.

### Added — Phase 6: Sync

- The offline queue now drains. `SyncWorker` became a `CoroutineWorker` that
  restores the session, finds the signed-in account and runs one synchronisation;
  a run that tried and failed returns `retry`, so WorkManager backs off and tries
  again on its own.
- `SyncEngine`: push what changed locally, then pull what changed elsewhere.
  Pushing first means a row this device just sent cannot come back as somebody
  else's change and overwrite itself.
- `ConflictResolver`, the whole policy in one pure, tested place: last write
  wins on `updated_at`, **except** that a local delete always beats a live server
  copy. That single rule is what stops a record deleted on a plane from returning
  when the phone finds a network.
- Deletions are sent as tombstones rather than as server-side deletes. A hard
  delete would simply vanish from the next download, so another device would keep
  its copy forever and the deletion would never spread.
- Download uses a per-account watermark (`sync_meta`, Room schema version 2, an
  additive migration), so a phone that syncs every fifteen minutes does not
  re-read the whole archive each time.
- `SyncTime` converts timestamps at the Room/server boundary. Room keeps naive
  local text; the server column is `timestamptz` and would otherwise read every
  record as UTC, shifting it by the device's offset.
- The profile screen shows how much is waiting, what the last run did and when,
  with a button to run one immediately.
- Failures are contained per table and per direction, and never reach the caller:
  a row that could not be sent is marked `SYNC_ERROR` and retried, and nothing is
  ever deleted locally because a request failed.
- Tests: `ConflictResolverTest`, `SyncEngineTest`, `SyncTimeTest`,
  `SyncMetaMigrationTest`.

### Added — Phase 5: Map

- The home screen is now an interactive map. It shows every memory that has a
  location and every event that has one, with an "I am here" button, an add
  button and a way into search.
- `MapProvider`, the abstraction the spec asks for: the app depends only on that
  interface, so the tile host can be swapped, or the renderer replaced with an
  SDK later, without touching a screen.
- A Compose slippy-tile renderer. It draws only the tiles inside the viewport,
  wraps columns across the antimeridian and skips rows past the projection, so
  panning has no blank edge. No map SDK is added, so nothing here can be stranded
  by an abandoned upstream library.
- `WebMercator`: the projection maths, pure and tested, shared by the tiles, the
  pins and the picker.
- Marker clustering in screen space (`MapClustering`). A sparse map shows every
  pin; a dense city collapses into countable bubbles that break apart as you zoom
  in. Cluster longitudes are averaged on the unit circle, so a group spanning the
  antimeridian is not thrown to Greenwich.
- Manual location picking: move the map until the centred pin is where you mean.
  A memory can now be pinned to a place while it is being created, and an
  existing pin survives editing anything else.
- Tiles are cached on disk through the same Coil image loader that serves photos,
  and every request carries a real user agent, which the OpenStreetMap tile usage
  policy requires. The tile host and its credit come from the build settings.
- `LocationReader` reads a position without Google Play services, one shot, only
  when the user presses for it, and always removes its listener.
- Tests: `WebMercatorTest`, `MapClusteringTest`, `TileServerMapProviderTest`.

### Added — Phase 4: Diary

- Diary event CRUD. A day's event now has a real editor: title, details, date,
  clock time and an optional emotion. Tapping an event on the day screen opens
  it, and deleting it keeps a tombstone for the next sync.
- The life timeline tab, which was a placeholder. Diary events and memories are
  merged into one chronological stream, newest day first; inside a day the timed
  events run morning to evening and the day's memories follow.
- The timeline reads a bounded window (60 days) and grows a window at a time on
  request, so a ten-year archive is never loaded all at once.
- The calendar browser on the previously dead `calendar` route: move between
  months and open any day. It reuses the month grid, so a dot means the same
  thing in both places.
- `TimelineBuilder`, a pure domain use case for the merge and ordering rules, so
  they are tested on the JVM with no database and no Android.
- `DiaryRepository.getEntry` and `MemoryRepository.watchBetween`, the two reads
  the editor and the timeline window needed.
- Tests: `TimelineBuilderTest` (ordering, grouping, tombstones, window bounds),
  `EntryEditorViewModelTest` and `TimelineViewModelTest`.

### Changed — Phase 4

- The month calendar grid moved to a shared `MonthCalendarGrid` used by both the
  month page and the calendar browser, instead of being drawn twice.
- Deleting an event on the day screen is now an explicit control, since the row
  itself opens the editor.

### Added — Phase 3: Memories

- Memory CRUD: create, read, update and soft-delete from the memories tab, with
  a detail screen and a single editor used for both create and edit.
- Photo, audio and video attachments. Photos are picked through the system photo
  picker or taken with the camera; voice notes are recorded in-app; videos are
  picked and played back locally.
- Files are copied into app-private external storage, so the archive survives a
  cache clear, is not indexed by the gallery and needs no storage permission.
- Camera and microphone permissions are requested at the moment of use, never at
  startup, and the recorder is released as soon as recording ends.
- Attachments are plain files by design: audio is never transcribed and video is
  never analysed or summarised. Only a thumbnail, a duration and a file size are
  read.
- `MediaRepository` with a per-owner aggregate query, so the memory list shows
  thumbnails and media counts without one query per row.
- A `deleted_at` tombstone on the `media` table. Removing an attachment deletes
  the file and keeps the row, so an offline delete is replayed by the sync worker
  instead of being resurrected.
- Abandoning the editor deletes the files that were imported during it, so
  nothing is left behind in app-private storage.
- Tests: `MediaImporterTest` (accepted file types, extensions, duration
  formatting), `MediaRepositoryImplTest` (attach, tombstone plus file removal,
  counts, summary, discard), `MemoriesViewModelTest` and
  `MemoryEditorViewModelTest`.

### Fixed — Phase 3

- "On this day" no longer includes the current year. The month-day pattern also
  matched today's own records, so the card could list the day it was shown on.
- The memory list, detail and editor no longer show the Phase 3 placeholder.

### Added — Phase 2: Authentication

- Email sign up, sign in, sign out and password reset through Supabase Auth.
- Session persistence in EncryptedSharedPreferences backed by the Android
  Keystore; the refresh token never reaches Room, the logs or a backup export.
- Automatic session restoration on cold start, so a signed-in user is not shown
  the sign-in form again (an `AuthState.Unknown` gate drives the splash).
- An offline local account: when no Supabase project is configured the app still
  has an identity and every feature works on-device. No password is stored or
  checked for it, because there is no credential to verify.
- Every query is now scoped to the signed-in user id; the diary streams restart
  when the account changes.
- Server failures map to stable `strings.xml` keys, so no server error text
  (which can contain the email) is shown on screen.
- Tests: `SupabaseAuthRepositoryTest` (offline path, session restore, sign out
  keeps the archive) and the day screen now tested against a signed-in user.

### Changed — Phase 2

- `SupabaseClientProvider` depends on the `SessionManager` interface, and the
  Auth plugin is installed with the Keystore-backed session manager.

### Added — Phase 1: Foundation

- Android project `com.memorymap` with `minSdk 26`, `compileSdk 36`, `targetSdk 36`,
  declared once in `app/build.gradle.kts`.
- Gradle version catalog (`gradle/libs.versions.toml`) as the single source of
  every dependency and plugin version.
- Jetpack Compose + Material 3 shell: light/dark theme, brand colors
  (`#1A237E` / `#6A1B9A`), Cairo for Arabic and Inter for English, bundled in
  `res/font` so typography needs no network.
- Arabic as the default locale with an English `values-en` copy and a
  `locales_config.xml` per-app language declaration; `supportsRtl="true"`.
- Navigation foundation with the five top-level destinations (map, diary,
  memories, timeline, account) and every parameterised route defined centrally.
- Quick-add bottom sheet reachable from any tab.
- Diary browsing that already works locally: diary home (today / yesterday /
  this week / this month / this year), the day screen with the end-of-day note
  and the ordered event log, the week page, the month calendar with content
  markers, and the year page.
- Room database v1 with the full schema: `users`, `memories`, `daily_entries`,
  `diary_notes`, `media`, `people`, `places` and the five link tables, plus a
  single per-day aggregation query feeding week/month/year/calendar.
- Repository pattern with domain interfaces, so no screen touches Room or
  Supabase directly.
- Synchronization state machine (`PENDING_CREATE`, `PENDING_UPDATE`,
  `PENDING_DELETE`, `SYNCED`, `SYNC_ERROR`) with soft deletes as tombstones.
- Supabase client foundation reading client-only keys from `local.properties`,
  running in offline-only mode when unconfigured.
- WorkManager periodic sync job, network-constrained, with a Hilt worker factory.
- Life statistics (recorded days, memories, events, places, people, photos,
  audio, videos, most used emotion, busiest month) counted from local data.
- "On this day" lookup by month and day across earlier years.
- Adaptive launcher icon with a monochrome layer.
- Unit tests: `DiaryTimeTest`, `GeoTest`, `BackupPlannerTest`,
  `MemoryRepositoryImplTest`, `DiaryDatabaseTest` (real Room via Robolectric),
  `DayViewModelTest`.
- CI workflow that verifies every dependency coordinate, runs the unit tests,
  lint and `assembleDebug`, and uploads the APK as an artifact.
- Privacy policy in Arabic and English, Apache-2.0 license, release and signing
  instructions, and the Supabase schema with Row Level Security policies.

### Explicitly not in this phase

The following are placeholders that state which phase implements them instead of
showing fake data: nearby (Phase 5 follow-up) and backup export/import
(Phase 8).
