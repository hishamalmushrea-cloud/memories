# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The Supabase schema is now executed instead of only read. `ci/check-schema.py`
  starts a throwaway PostgreSQL - the system one when it exists, otherwise the
  server the `pgserver` wheel bundles - creates the shapes the migration assumes
  (`supabase/verify/00_supabase_stubs.sql`: `auth.users`, `auth.uid()`,
  `auth.role()`, `storage.buckets`, `storage.objects`, `storage.foldername()`, the
  three roles and Supabase's own default privileges), applies `supabase/schema.sql`
  **twice** from its real path with `ON_ERROR_STOP`, and runs
  `supabase/verify/10_checks.sql`: twenty-two behavioural checks performed as
  `authenticated` and as `anon` rather than as the owner of the tables, so nothing
  bypasses Row Level Security. It then reads the table and RPC names out of the
  client's own code and fails if the server does not have them. The whole thing
  takes about a second, and it is wired into `build.yml` as a hard gate.

- Deleting the account from the app itself, which until now was something only
  the Supabase dashboard could do. The profile screen has two separate actions by
  design: the local wipe (which now also offers to delete the records from the
  server, and deletes the uploaded files when it does, because their keys live on
  the rows that are about to go) and a confirmed account deletion that calls
  `delete_my_account()`, removing the records, the profile row and the auth user
  so the email address can be used again. The order is the guarantee: the device
  is wiped only after the server confirms, and a failed request leaves both
  halves untouched and says so in as many words. Both functions are
  `security definer`, act on `auth.uid()` only and are revoked from `public`.

### Changed

- Seven strings that carry a count became `<plurals>`, because the number and the
  noun have to agree and Arabic does not have one form for that: "حدث واحد",
  "حدثان", "٥ أحداث" and "١٥ حدثًا" are four different sentences. The week, month,
  year, timeline, people and places screens called `stringResource` with a number
  and always got the same wording, so one event read "1 حدث" and three read
  "3 حدث". They now call `pluralStringResource`, and the Arabic file supplies all
  six quantities for each. A sentence with two independent counts in it
  (`wipe_done_body`, the month totals) is keyed on the first count: Android
  plurals can only inflect one.

- The launcher icons moved from `mipmap-anydpi-v26` to `mipmap-anydpi`. At
  `minSdk = 26` every device that can install the app is past the version
  qualifier, so it was a directory name that meant nothing.

- `Uri.parse` and two `Uri.fromFile` calls in the backup writer became the
  `androidx.core.net` extensions (`toUri`), which is the same code with one less
  conversion to read.

- The lint part of the CI report now lists up to six locations per rule and the
  message of its first occurrence, instead of a count and one location. A count on
  its own cannot say which six strings a rule wants turned into plurals.

### Fixed

- On Android 12 and later nothing was declared about backup extraction, which is
  what the `DataExtractionRules` lint check points at: the platform reads
  `android:dataExtractionRules` from API 31 up, and `android:allowBackup="false"`
  is no longer the whole statement. There is now a rules file that excludes every
  domain by name, in both the cloud-backup and the device-transfer halves, so the
  app's private storage - the memories, the diary, the media - is not part of a
  platform backup or a phone-to-phone transfer. The app's own export folder in
  Settings remains the one copy the user chooses to make.

- Two colours nothing referenced (`memorymap_primary`, `memorymap_on_primary`)
  were left over from a theme that is now built in Kotlin, and both were reported
  as unused resources.

- The migration could not be applied twice. `create type`, `create table`,
  `create index`, `create policy` and the six `updated_at` triggers all failed on
  a second run, which is what a half-finished paste into the SQL Editor followed by
  a second paste looks like: an error in the middle of a migration that is half
  applied. Types are now guarded with `duplicate_object`, tables and indexes are
  `if not exists`, every policy is dropped before it is created, and every trigger
  is dropped before it is created.

- `places.sync_status` and `people.sync_status` were `text` while the same column on
  the other four synchronised tables was the `sync_state` enum, so two of the six
  would have accepted a status the client could not parse back. All six are the
  enum now, and a check in `supabase/verify/10_checks.sql` reads the applied
  schema's column types rather than the file's text to say so.

- `anon` could execute `delete_my_data()` and `delete_my_account()`. The schema
  revoked them from `public` and granted them to `authenticated`, which is not
  enough on a real project: Supabase sets `alter default privileges in schema
  public grant all on functions to postgres, anon, authenticated, service_role`,
  so every new function is executable by the role whose key ships inside every
  APK. The revoke now names `public, anon` for both deletion functions and for the
  two trigger functions, and the check that found this now fails the build if an
  anonymous caller can execute either one.

- `SupabaseAuthRepository.deleteAccount` declared `AuthRepository.Deletion` and
  ended with a `runCatching { ... }.fold(...)` chain, which reads as a return and
  is not one: in a block body Kotlin throws the value of the last statement away.
  The compiler said one line - `Missing return statement` - after a push, and
  `ci/check-returns.py` now finds that shape before Gradle runs, by walking the
  last statement of every function that declares a non-`Unit` value back to its
  own beginning. It is deliberately quiet about anything it cannot prove: a
  `throw` anywhere in the body, a `Nothing` return type, or a body ending in a
  control-flow construct all pass, because a guard that fires on valid code costs
  more than the mistake it prevents. It counts what it examined and fails if that
  number is implausibly small - its first version matched nothing at all, and
  passed.

- Two English strings contained an unescaped apostrophe (`device's`), which aapt2
  refuses with "Invalid unicode escape sequence in string" - naming neither the
  character nor the reason, and failing the resource merge, and with it the unit
  tests, the lint run and every APK. `ci/check-strings.py` now reads both locale
  files before Gradle starts and fails on a bare apostrophe, an escape Android
  does not accept, a resource that exists in one language only, an Arabic plural
  missing one of its six categories, or a translation that dropped a `%1$d`.

- The "could not be removed from the cloud" message told the user that deleting
  the account from the Supabase dashboard would take the remaining attachments
  with it. It would not: `storage.objects` is not a child of `auth.users`, so
  nothing cascades into it, and files whose keys were already gone would simply
  stay in the bucket. The message now says the bucket survives the account and
  points at the two things that do work.

## [0.1.0] - 2026-09-26

The first release. Everything below was written before it: the ten phases the
prompt asks for, in order, each one built, tested and reviewed before the next.

### Fixed

- A memory edited on one device while another was syncing could stay on the first
  phone and never reach the second, with no error and no retry. The download
  window was wrong twice over. The watermark was stored as the naive local
  rendering of the newest server stamp and sent back as the lower bound of a
  `timestamptz` comparison, where a value without an offset is read in the
  session's timezone: a device three hours ahead of UTC asked for rows newer than
  a moment three hours in the server's future and skipped everything another
  device changed in that window. The watermark is now the server's own instant,
  carried separately from the local rendering used for comparison. The stamps
  themselves were the client's edit times, so a row edited offline at 09:59 and
  uploaded at 10:05 carried a stamp behind the 10:00 watermark of a device that
  had already synced - the window is now inclusive, so that boundary row is
  re-read and the resolver discards it as unchanged, and the schema stamps
  `updated_at` on arrival with `greatest(client value, now())` so a row written
  after a watermark is always greater than it.

- A Kotlin file with an unbalanced bracket is now caught in a second, before
  Gradle starts, instead of fifteen minutes into a CI run that reports it as
  "Expecting a top level declaration" somewhere else in the file. That is not
  hypothetical: it happened, in the contract test, and the message pointed at a
  different line than the mistake. `ci/check-balance.py` walks every Kotlin file
  past strings, raw strings, character literals and comments, and fails when a
  bracket has no partner. It is not a parser and does not pretend to be: types,
  names and arity still need the compiler, which still runs on every push.

- The account row in `profiles` now exists before anything is uploaded, which it
  did not. Every table's `user_id` is a foreign key to `public.profiles`, nothing
  in the app wrote that row, and the schema had no trigger creating it either:
  the first real sync against a real Supabase project would have failed on that
  foreign key for every table and every user, with a message about a key not being
  present in a table the client never touches. The schema now creates the row when
  the auth user is created, and the app writes it too when a session appears, so
  a project whose schema predates the trigger still syncs and the display name the
  user typed can reach the server. Both halves are pinned by a test that reads the
  schema and the client source, because no local test can see a server constraint.

- The end-of-day note now synchronises, which it never did. It was written into
  Room, `supabase/schema.sql` had a `diary_notes` table with its own policy
  waiting for it, and nothing carried it to the server: sign in on another device
  and the events of a day were there while the note about them was not. The table
  now takes part in a sync run like the others, with two differences that come
  from the schema - the key is `(user_id, note_date)` rather than an id, so the
  engine carries the pair as one handle that SQL and Kotlin compose the same way,
  and there is no `deleted_at`, so clearing the note travels as an empty body
  instead of a tombstone. The profile screen's "waiting to sync" count now
  includes notes too, which it did not.

- The uploaded copies of your attachments can now be deleted, which they could
  not be before. Deleting your local archive left them in the bucket with
  nothing able to reach them: the keys live on the rows the wipe removes, so
  after it ran the app had lost them for good. The confirmation now says how
  many attachments you uploaded and offers to delete them, unchecked by default
  — the button promises to clear *this device*, and a cloud copy may be the only
  one another device can still fetch. Whatever is left over is reported by count
  in the result, because that is the last moment the app can tell you.

  The privacy policy said the app would not touch cloud data at all. It now says
  what the app actually does: files it uploaded, on request, and never the
  account itself.

### Added

- The release pipeline is prepared and rehearsed, and the app is still published
  nowhere. The store listing now exists as files that both F-Droid and Google
  Play read - `fastlane/metadata/android/{ar,en-US}/` with the title, the short
  and full descriptions and a changelog per `versionCode` - and
  `ci/check-store-metadata.py` fails the build when a field is over the limit the
  stores enforce or when a version bump arrives without its changelog file. The
  release workflow can be run by hand to rehearse a release: it runs the security
  gate, the metadata check, the tests, `lintRelease`, builds both the APK and the
  AAB and prints what a tag push would publish, then stops. Only `refs/tags/v*`
  reaches the publishing step. The signing, F-Droid and Play steps, the Data
  safety answers and the still-missing store images are written down in
  `docs/RELEASE.md`, including that screenshots must come from a real device
  rather than a mock-up. The AAB that Google Play accepts is now built on every
  push - `assembleRelease bundleRelease` - and the build fails when it is
  missing, so the Play artifact is verified per commit instead of first at
  release time. A hand-run rehearsal of the release workflow is only possible
  once the workflow is on the default branch, which is written down where the
  command is.

- "Near by" is a screen instead of a note saying which phase would build it. It
  lists the memories and events inside one kilometre of a position you ask for,
  closest first, each with its distance and its day, and tapping one opens it. The
  position is read once, when the button is pressed, and the screen says plainly
  that it has not been read before that: there is no tracking, and no record of
  where you might have been. A position that cannot be read says so, and an empty
  radius explains which of the two it is - nothing located at all, or nothing
  nearby. It is reachable from the map and from the top of the timeline. The
  filter and the ordering are `Geo.nearby`, and the screen is covered by
  `NearbyViewModelTest` against a real database.

- The camera can now be used from inside the app, which is what the images
  specification asked for. *Take photo* opens a CameraX preview instead of
  handing a file to whichever camera app the phone happens to have: the picture
  is written straight into the app's own archive, so no storage permission and no
  FileProvider grant are involved, and it is rotated the way the phone was held
  at the moment it was taken rather than left for a later step to notice. The lamp
  has three settings — off, automatic, on — remembered per lens, and the front
  lens offers none because it has none. The camera is unbound the instant the
  screen is left, so it is never held open behind another screen. A device with no
  camera, or a refused permission, gets a clear message and keeps the gallery
  picker. Recording video in the app is not part of this change; picking a video
  still goes through the system gallery.

- Photos are prepared before they leave the device, as the medium specification
  asks. An upload is not the file: a JPEG is turned the way its Exif orientation
  says, scaled to a longest edge of 2048 pixels and written at quality 82, and the
  rewritten copy is used only when it came out smaller than the original — so
  "compression" can never cost you bytes. A photo already upright and inside the
  limit is not re-encoded at all: its metadata segments are removed and its pixels
  are copied over byte for byte. The location, the camera model and the timestamp
  therefore do not reach the bucket, while the file on the device keeps them.
  Whatever this cannot rewrite faithfully is uploaded as it is — a PNG, a GIF, an
  HEIC, or a photo whose stored orientation cannot be read — and a photo whose
  pixels have to be turned is never parted from its Exif block unless the pixels
  were turned first. The header reader, the size rules and the eight orientation
  transforms are all unit tested, including where each corner of a photographed
  test pattern ends up.

- Attachments can be sent to the cloud, one at a time and only when asked.
  Nothing uploads on its own: an attachment carries an explicit request, the
  sync worker carries it out, and until then the row shows that it is waiting.
  Removing an uploaded attachment deletes the object from the bucket as well,
  and hides the count of attachments that have left this device.

  The bytes go to a key of the form `{user id}/{attachment id}.{extension}`.
  That shape is not a preference: the storage policies in `supabase/schema.sql`
  decide access with `(storage.foldername(name))[1] = auth.uid()::text`, so an
  object stored anywhere else belongs to nobody and is refused by the bucket.
  The row is written after the bytes for the same kind of reason - the server's
  `storage_path` is `not null`, so a row naming an object that is not there yet
  would be a lie the next device would act on.

  An attachment arriving from another device brings its bytes with it. The local
  file is never removed by any of this, so a copy is added, never moved; and an
  attachment nobody opted into never leaves this device at all, which is why it
  has nothing to delete on the server when it is removed.

  `media` gained `updated_at` and `deleted_at` on the server, and the local table
  gained `updated_at`, `storage_path` and `upload_requested` in migration 3 to
  4. The stamp is the load-bearing part: a download asks for rows changed since a
  watermark, and an attachment deleted on another device keeps its original
  `created_at`, so without a stamp that moves, the deletion would fall outside
  every later window and never arrive.

- A build guard that reads every Room `@Query` and fails when it is not the query
  it appears to be: when the SQL names a parameter the function does not declare,
  or when two string literals stand next to each other with no `+` between them.
  Kotlin does not join those, so the query silently becomes its first fragment -
  seven shipped that way once, and five of them compiled happily while losing
  their `ORDER BY` and their `deleted_at IS NULL` clauses.

- Video can be recorded inside the app rather than only picked from the gallery,
  which is the other half of what the video specification asks for. The camera
  has two modes, and the video one works a single button: press to start, press to
  stop, with the elapsed time on screen. The recording is plain media — it is
  never transcribed, analysed or summarised. Sound is captured only when the
  microphone permission is already held; without it the video is recorded
  silently and says so, rather than interrupting a shot with a permission dialog.
  A recording shorter than a second, or one the camera ended with an error, is
  deleted instead of being attached, so the archive holds no half-file. Playback
  already used the system player, and picking a video from the gallery is
  untouched.

### Removed

- The `FileProvider` the app declared. It was there to hand a photo file to
  whichever camera app the phone happens to have; now that the photo is taken
  inside the app nothing referenced it, and a provider that grants URI access to
  another app should not stay declared out of habit.

### Fixed

- Backups were written but could never be read back. `DocumentFile.createFile`
  appends the extension of the MIME type it is given, unconditionally, so
  asking for `manifest.json` with `application/json` produced
  `manifest.json.json`, and asking for an attachment with
  `application/octet-stream` produced `name.jpg.bin`. The reader looks
  documents up by the names the format defines, so every export was an archive
  that could not be inspected or restored. Documents are now created with a
  MIME type that has no extension, and the created name is checked afterwards,
  because a provider is free to adjust it - a rename now fails the write and is
  reported, instead of succeeding quietly and producing an unusable archive.
  This was found by the first tests the backup repository has ever had.

### Added

- A build guard that fails in under a second when a plain function calls a
  suspend DAO method. Kotlin rejects that at compile time, which meant the
  mistake cost a whole ten-minute build cycle to discover - three times over.
  It runs before Gradle now, so it reports itself immediately.

- The three new strings about uploaded copies are plurals rather than a number
  substituted into a sentence. Arabic says one attachment, two attachments and
  eleven attachments three different ways, and `(%1$d مرفقًا)` was wrong for two
  of those three. This is what the newly-listed `PluralsCandidate` warnings
  pointed at.

- The CI report now lists lint warnings by rule with a count and one example
  location. It already listed compiler warnings that way; lint printed only a
  total, which is why a run that added three of them could not say which three.

- A third build guard, for an `import` that follows a declaration, which Kotlin
  allows only at the beginning of a file. It comes from appending to an import
  when a patch meant to add a file-level constant.

- A second build guard, for a comment that separates a declaration's modifiers
  from the declaration. It comes from anchoring a patch on a substring of a
  declaration and splicing a documented block in front of it, which reads as
  valid code and is rejected by the compiler a build cycle later - the same
  shape of mistake the first guard exists for. Both run before Gradle.

- Tests for the backup repository, which had none. They run against a real
  archive on a real disk: the one part that cannot run here is the Storage
  Access Framework grant, so `BackupArchive.root` became a seam and the tests
  point the archive at an ordinary directory. Everything below it - the
  serialisation, the merge decision, the database - is the code under test.
  What they pin down is the rule an import exists to keep: it never overwrites
  a record this device edited more recently, and it never resurrects one the
  user deleted.

- The links between records and the people and places they mention now
  synchronise. A link carries no timestamp of its own, so there is nothing to
  resolve a conflict with and no queue of its own to keep: it travels with the
  record that owns it, whose `updated_at` already moves when its links change,
  and it is replaced wholesale in both directions. That is what makes an unlink
  reach another device instead of being merged back in.
- `DailyEntryDao.replacePeople` and `replacePlaces`, which the memory side
  already had. Both are whole-set replaces inside a transaction, so removing a
  name in the editor really removes it.

### Added

- People and places now synchronise, which they never did. Until now both tables
  were local-only, so reinstalling the app - or signing in on a second device -
  silently lost every name the user had built up. Room moves to version 3, which
  gives `people` and `places` the four columns synchronisation reads, and a
  migration backfills `updated_at` from `created_at` so an installed archive is
  queued for one upload rather than assumed to be on the server already.
- They are pushed *before* memories and events. The server's link tables carry a
  foreign key to them, so a memory that mentions a person can only be accepted
  once that person exists there; sending the referenced rows first is what keeps
  a first sync from failing on the very link it is trying to store.

### Changed

- Deleting a person or a place is now a tombstone rather than a removal, so the
  deletion reaches other devices instead of the name quietly reappearing on the
  next download. Every read path excludes tombstones, and typing a deleted name
  again revives the old row - keeping its id, so the records already linked to it
  are not orphaned, and staying clear of the unique `(user_id, name)` index.

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

[Unreleased]: https://github.com/hishamalmushrea-cloud/memories/commits/main
[0.1.0]: https://github.com/hishamalmushrea-cloud/memories/releases/tag/v0.1.0
