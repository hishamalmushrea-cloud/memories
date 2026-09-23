# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
showing fake data: authentication (Phase 2), memory CRUD and media capture
(Phase 3), the map and nearby (Phase 5), the sync upload pipeline (Phase 6),
search/people/places/timeline filters (Phase 7), and backup export/import
(Phase 8).
