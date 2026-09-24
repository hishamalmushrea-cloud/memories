# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
showing fake data: nearby and search/people/places filters (Phase 7), the sync
upload pipeline (Phase 6), and backup export/import (Phase 8).
