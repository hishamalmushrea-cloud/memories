# Privacy Policy — Memory Map

Last updated: 23 September 2026

## 1) The basic principle

This app is **offline-first**. Your memories, diary, photos, recordings and videos
are stored on your device. Linking a cloud account is optional, never required to
use the app.

## 2) What we never do

- No ads.
- No tracking.
- No analytics.
- No selling of data.
- No advertising or analytics SDKs from any third party.
- No audio-to-text transcription.
- No video analysis or summarisation.
- No AI used to write, interpret or summarise user content.

## 3) Location

- There is no continuous location tracking and no background location recording.
- Location is read **on demand only**, in these cases:
  - Opening the map and tapping "I am here".
  - Creating a memory and choosing automatic location.
  - Creating an event and choosing to attach a location.
  - Picking a location manually on the map.
- The app is fully usable without granting the location permission.
- Coordinates are never written to logs and never sent to a service that does not
  need them.

## 4) Media

- Photos, audio recordings and videos are stored in the app's private storage on
  your device.
- Video stays local by default; uploading it is an explicit, optional action.

## 5) When you connect a Supabase account (optional)

- Only the **client-side anon key** is used. The `service_role` key is not present
  in the app and must never be, because everything inside an APK is public.
- All traffic uses HTTPS.
- The session is stored using a modern secure storage mechanism (Android Keystore).
- **Row Level Security** guarantees that:
  - `PRIVATE` → the owner only.
  - `SHARED` → the owner plus the users listed in `MemoryShare`.
  - `PUBLIC` → according to the app policy, and never the default choice.
- Diary entries are always `PRIVATE` by default.

## 6) Limits of external services

We do not assume that a free service is unlimited. Storage, bandwidth, database
and map providers all have quotas, so the app keeps working locally even when the
cloud service is temporarily unavailable.

## 7) Your data and your control over it

- **Export**: you can export your whole archive locally (JSON plus a media folder).
- **Import**: you can restore that archive.
- **Deletion**: you can delete an event, a memory, a photo, a video, a recording,
  your local data, and your account together with its cloud data. A clear
  confirmation always precedes a permanent deletion of sensitive data.
- **No operating-system backup**: the app opts out of Android's automatic
  backup, so your database and your media are never copied to a third party's
  servers by the system. If you want a copy, you take one yourself with
  **Export**, to a folder you choose. This is a deliberate trade: an automatic
  backup would be convenient, and it would also mean your diary leaving your
  device without the app ever telling you.
- **Encrypted in transit**: any connection to a Supabase project is required to
  be HTTPS. A project configured over plain HTTP is treated as not configured at
  all, and the app simply stays offline rather than sending your session or your
  archive where it could be read on the way.

## 8) Children

The app is meant for personal use and collects no data for any commercial purpose.

## 9) Contact

For any privacy question, open an issue in the project repository on GitHub.
