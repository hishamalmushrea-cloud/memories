# Manual QA — what only a phone and a project can prove

Everything in this file is a claim the app makes that **no test in this repository
can check**. The unit suite runs on the JVM with Room, Robolectric and fakes at the
network boundary; CI runs on a machine with no camera, no GPS, no screen and no
Supabase project connected to it. Every box below is a thing a person has to do,
and the observation column is what "it works" looks like when it does.

**The app has not been through this list yet.** It is written to be walked, not to
be quoted: until the boxes are ticked, the answer to "how do you know it works?" is
"the tests pass", not "it was tried".

### The refusal of a file the project cannot take

- [ ] Attach a video larger than the project's per-file limit (50 MB by default) to a
      memory, then switch its cloud button on. A message must appear naming the limit in
      megabytes, and the attachment must **not** be queued: check in the profile screen
      that the pending count did not move.
- [ ] The same video on another memory, with the cloud button left off: nothing should
      be said at all, because nothing was refused.
- [ ] A photo over 50 MB on disk but under it after preparation (a large phone photo)
      must be accepted and must arrive in the bucket. This is the case a naive size
      check at the button would have broken.
- [ ] With the cloud button on for a file that is accepted, force a sync with no network:
      the attachment stays queued, and no message about size appears.
- [ ] In Arabic and in English, the message names the limit and does not read "0 MB".

#### Failures the app should say out loud

- [ ] Add a person to a memory while the database is fine — nothing is said. (To see the
      failure path by hand, the surest way is to fill the storage or kill the database;
      these are listed so the *wording* is checked when it does happen.)
- [ ] Delete a memory from the editor and from the list: on success the row goes, and on
      failure something must be said rather than nothing happening.

## Before you start

- A physical Android device, API 26 or newer, with a camera, a microphone and GPS.
- A Supabase project of your own, with `supabase/schema.sql` applied (README,
  "التحقق من مخطط Supabase"), and `local.properties` pointing at it.
- A signing key and the four `MEMORYMAP_KEY*` secrets if you are testing a release
  build (docs/RELEASE.md).
- Two devices, or one device and one emulator, for the sync checks. Second run of
  the app on the same phone does not prove sync: it proves caching.

## 1) First run, with nothing configured

| Step | Expected observation |
|---|---|
| Install and open the app on a phone with no `local.properties` values | It opens. No crash, no login wall, no empty red error box. |
| Look at the bottom bar | Five destinations, labels in Arabic (or the phone's language), the first one selected. |
| Open Settings → about/privacy | The privacy text is readable in the app's language. |
| Turn airplane mode on and use the app | Everything local still works: writing a memory, the diary, the timeline. |

## 2) The core round trip: a memory with a place, a photo and a person

| Step | Expected observation |
|---|---|
| Create a memory, type a title and text | It saves without a network. |
| Pick a location on the map | The pin lands where you tapped; the place name field is offered. |
| Attach a photo from the gallery | A thumbnail appears; reopening the editor still shows it. |
| Add a person and a place | Both appear on the memory's card. |
| Open the map tab | The memory is a marker at the place you picked; the map is not grey and carries an attribution line. |
| Rotate the phone and open the memory again | Same title, same text, same location, same photo. |

## 3) The diary and the timeline

| Step | Expected observation |
|---|---|
| Write a diary event with a time | It appears under that day, in time order. |
| Open the week view | The event is counted in that day's cell. |
| Open a day, then the month, then the year | Each level counts what the level below shows; nothing is off by one. |
| Open the timeline | Events are ordered by time, newest first, with dates in the device's own time zone. |

## 4) Sync, on a real project, with two devices

| Step | Expected observation |
|---|---|
| Sign in on device A | Sign-in succeeds; no key or token is visible in the UI or in logcat. |
| Sync A | The status chip stops saying pending, and the run reports what it sent. |
| Sign in as the same account on device B, sync | The records arrive: memories, diary, people, places, and the links between them. |
| Edit a memory on B, sync B, then sync A | A shows B's edit. |
| Delete a memory on A while offline, then sync A | It does not come back on A, and B, once it syncs, does not show it either. |
| Put A in airplane mode and edit | The record stays visible and marked pending; nothing is lost when the app is closed and reopened. |
| Turn the network back on and sync | The pending edits go up and the pending markers clear. |
| Set the phone's clock forward a day, edit, set it back, sync | No edit is lost and no edit is duplicated. This is the case the timestamp work exists for. |
| Sign out on A | Local data for that account is no longer shown. |
| Delete the account in the app | It asks first, names what will be deleted, and the local and cloud copies are gone afterwards. |

## 5) Media, camera and permissions

| Step | Expected observation |
|---|---|
| Take a photo in the app | It is saved and attached; the original is not damaged. |
| Record a video in the app | It records, stops, and plays back from the memory. |
| Delete a photo's file with a file manager, then open the memory | The memory still opens, and says the attachment is missing instead of crashing. |
| Deny the camera permission, then try to capture | The app explains what it needs and does not crash. |
| Deny the location permission, then try to pick a place | It says so and offers manual map picking. |
| Check Settings → Apps → Memory Map → Permissions | No background location. Nothing asks for storage access. |
| Leave the app open for ten minutes, then check the permission log | No location request happens while the app is in the background. |

## 6) Backup and restore, the hard way

| Step | Expected observation |
|---|---|
| Export to an SD card | The archive is written where you chose, with `manifest.json` and the JSON documents beside it. |
| Connect the card to a computer and open the files | They are readable text; the photos are in `media/`. |
| Wipe the app's data, reinstall, sign in as the same account, import | Memories, diary, people, places come back with their links and their attachments. |
| Import the same archive twice | The second run reports that nothing was new and overwrites nothing. |
| Delete a memory, then import an older archive that still has it | The deleted memory does not come back. |
| Restore an archive while signed in as a *different* account | Note what happens. The rows are restored under the archive's account and are invisible and never uploaded; nothing leaks, but nothing warns you either. This is the known limitation in docs/READINESS.md. |

## 7) Layout, language and accessibility

| Step | Expected observation |
|---|---|
| Switch the phone to English | Every screen is translated; nothing is half-Arabic. |
| Switch back to Arabic | The layout is right-to-left: back arrows point the right way, text starts on the right, numbers still read left-to-right. |
| Switch to a large font size (200%) | Nothing is cut off and no button is unreachable. |
| Turn on TalkBack and walk the five tabs | Every tab, button and field is announced with a name, not as "button". |
| Turn on dark theme | Everything is readable; no screen keeps a light background. |
| Set the system clock and time zone to something else, then reopen the app | Times still read as the moment they happened, in the new zone. |

## 8) Release build on the phone

| Step | Expected observation |
|---|---|
| Install the release APK (R8 minified) | The app starts and works as it does in debug. |
| Open the app and watch logcat for its own tag | No debug logging from this app in a release build. |
| Try to reach the app's screens without a network for an hour | Still usable offline. |
| Check the APK with `apksigner verify` | Signed with the release key, not the debug one. |

## What a green run here means, and what it does not

A fully ticked list means the app does what it says on one phone, with one
project, on the day it was checked. It does not mean the Play listing exists, that
the app is published, or that every device and Supabase plan behaves the same. The
specification is explicit about this: the app must not be described as published
before it is (MemoryMap_Full_Prompt.md §54).
