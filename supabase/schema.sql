-- =============================================================================
-- Memory Map — Supabase schema and Row Level Security
--
-- Apply it in the project's SQL Editor, before signing in on any device.
--
-- Applying it again is safe: types, tables, indexes, triggers and policies are
-- all written to be re-created, so a paste that was interrupted half way through,
-- or a second paste after an edit, lands on the same state instead of on an
-- error. `ci/check-schema.py` applies it twice to a real PostgreSQL for that
-- reason, and then asserts what the app depends on.
--
-- Status: the client has never been pointed at a running Supabase project.
-- What has been verified is narrower and worth stating exactly: the file applies
-- twice and passes its behavioural checks - isolation between accounts, the three
-- visibilities, the storage folders, the two deletion functions - on a real
-- PostgreSQL 16 with `supabase/verify/00_supabase_stubs.sql` standing in for the
-- parts of the platform the file assumes. GoTrue, PostgREST and the storage
-- service are not part of that, so a first run against a real project is still a
-- first run.
--
-- `SupabaseContractTest` reads this file together with the Kotlin records and
-- fails the build when the two disagree about a table, a column, an enum or a
-- key, which is what a server would otherwise have to tell a user.
--
-- Rules enforced here
--   PRIVATE  -> the owner only
--   SHARED   -> the owner + users listed in memory_shares
--   PUBLIC   -> per app policy, and never the default for a diary entry
--
-- The service_role key is never shipped inside the Android app. Everything the
-- client does goes through these policies.
-- =============================================================================

create extension if not exists "pgcrypto";

-- -----------------------------------------------------------------------------
-- Enumerations
-- -----------------------------------------------------------------------------
do $$
begin
    create type visibility_level as enum ('PRIVATE', 'SHARED', 'PUBLIC');
exception when duplicate_object then null;
end
$$;

do $$
begin
    create type emotion_level as enum ('HAPPY', 'SAD', 'LOVE', 'FEAR', 'PRIDE', 'NOSTALGIA');
exception when duplicate_object then null;
end
$$;

do $$
begin
    create type sync_state as enum (
        'PENDING_CREATE',
        'PENDING_UPDATE',
        'PENDING_DELETE',
        'SYNCED',
        'SYNC_ERROR'
    );
exception when duplicate_object then null;
end
$$;

do $$
begin
    create type media_kind as enum ('PHOTO', 'AUDIO', 'VIDEO');
exception when duplicate_object then null;
end
$$;

-- -----------------------------------------------------------------------------
-- profiles: one row per auth user
-- -----------------------------------------------------------------------------
create table if not exists public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    email        text not null,
    display_name text not null default '',
    avatar_url   text,
    created_at   timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- places and people: the two linkable nouns
-- -----------------------------------------------------------------------------
create table if not exists public.places (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles (id) on delete cascade,
    name       text not null,
    latitude   double precision not null,
    longitude  double precision not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    sync_status sync_state not null default 'SYNCED',
    last_synced_at timestamptz,
    -- Names are unique per user here and in Room, so one device cannot create the
    -- same name twice. Two devices can: the client merges on the primary key, and
    -- the second name of a pair would collide on this constraint instead of
    -- merging, leaving that row in SYNC_ERROR until the names differ. Worth
    -- knowing before blaming the network for one stubborn place.
    unique (user_id, name)
);

create table if not exists public.people (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles (id) on delete cascade,
    name       text not null,
    created_at timestamptz not null default now(),
    -- Sync columns. A deleted person stays as a tombstone so the deletion
    -- reaches every device instead of silently reappearing on the next pull.
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    sync_status sync_state not null default 'SYNCED',
    last_synced_at timestamptz,
    unique (user_id, name)
);

-- -----------------------------------------------------------------------------
-- memories
-- -----------------------------------------------------------------------------
create table if not exists public.memories (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references public.profiles (id) on delete cascade,
    title          text not null,
    body           text not null default '',
    latitude       double precision,
    longitude      double precision,
    place_name     text,
    memory_date    date not null,
    emotion        emotion_level not null default 'NOSTALGIA',
    visibility     visibility_level not null default 'PRIVATE',
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz,
    sync_status    sync_state not null default 'PENDING_CREATE',
    last_synced_at timestamptz
);

create index if not exists memories_user_date_idx on public.memories (user_id, memory_date desc);
create index if not exists memories_location_idx  on public.memories (user_id, latitude, longitude)
    where latitude is not null and longitude is not null;

-- -----------------------------------------------------------------------------
-- daily entries: the day is the unit, the event is what happens inside it
-- -----------------------------------------------------------------------------
create table if not exists public.daily_entries (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references public.profiles (id) on delete cascade,
    entry_date       date not null,
    entry_time       timestamptz not null,
    title            text not null,
    body             text not null default '',
    latitude         double precision,
    longitude        double precision,
    place_id         uuid references public.places (id) on delete set null,
    emotion          emotion_level,
    linked_memory_id uuid references public.memories (id) on delete set null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    sync_status      sync_state not null default 'PENDING_CREATE',
    last_synced_at   timestamptz
);

create index if not exists daily_entries_user_date_idx on public.daily_entries (user_id, entry_date desc, entry_time asc);

-- -----------------------------------------------------------------------------
-- diary notes: the free-form "what did I do today?" text, one per user per day
-- -----------------------------------------------------------------------------
create table if not exists public.diary_notes (
    user_id        uuid not null references public.profiles (id) on delete cascade,
    note_date      date not null,
    body           text not null default '',
    updated_at     timestamptz not null default now(),
    sync_status    sync_state not null default 'PENDING_UPDATE',
    last_synced_at timestamptz,
    primary key (user_id, note_date)
);

-- -----------------------------------------------------------------------------
-- media
-- -----------------------------------------------------------------------------
create table if not exists public.media (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references public.profiles (id) on delete cascade,
    owner_type     text not null check (owner_type in ('MEMORY', 'DAILY_ENTRY')),
    owner_id       uuid not null,
    media_type     media_kind not null,
    storage_path   text not null,
    mime_type      text,
    width          integer,
    height         integer,
    duration_ms    bigint,
    created_at     timestamptz not null default now(),
    -- Every synchronised table needs a stamp that moves when the row changes,
    -- because a download asks for rows changed since a watermark. An attachment
    -- keeps its created_at forever, so without this a deletion would fall
    -- outside every later window and never reach another device.
    updated_at     timestamptz not null default now(),
    sync_status    sync_state not null default 'PENDING_CREATE',
    last_synced_at timestamptz,
    deleted_at     timestamptz
);

create index if not exists media_owner_idx on public.media (owner_type, owner_id);
create index if not exists media_user_idx  on public.media (user_id);

-- -----------------------------------------------------------------------------
-- links
-- -----------------------------------------------------------------------------
create table if not exists public.memory_person (
    memory_id uuid not null references public.memories (id) on delete cascade,
    person_id uuid not null references public.people (id) on delete cascade,
    primary key (memory_id, person_id)
);

create table if not exists public.memory_place (
    memory_id uuid not null references public.memories (id) on delete cascade,
    place_id  uuid not null references public.places (id) on delete cascade,
    primary key (memory_id, place_id)
);

create table if not exists public.daily_entry_person (
    entry_id  uuid not null references public.daily_entries (id) on delete cascade,
    person_id uuid not null references public.people (id) on delete cascade,
    primary key (entry_id, person_id)
);

create table if not exists public.daily_entry_place (
    entry_id uuid not null references public.daily_entries (id) on delete cascade,
    place_id uuid not null references public.places (id) on delete cascade,
    primary key (entry_id, place_id)
);

create table if not exists public.memory_shares (
    memory_id           uuid not null references public.memories (id) on delete cascade,
    shared_with_user_id uuid not null references public.profiles (id) on delete cascade,
    role                text not null default 'viewer',
    created_at          timestamptz not null default now(),
    primary key (memory_id, shared_with_user_id)
);

-- =============================================================================
-- updated_at: stamped on arrival, so a download window cannot step over a row
-- =============================================================================
-- Every download asks for rows changed after a watermark, and the watermark is
-- the newest stamp the device has already seen. That only works if the stamps
-- grow in the order rows are written. They do not, on their own: the client sends
-- its own edit time, so a phone that edited a memory at 09:59 and uploaded it at
-- 10:05 stores 09:59 - behind another device's 10:00 watermark, and that row is
-- then never downloaded anywhere. It is not a rare race: any edit made offline
-- and sent after another device has synced lands behind its watermark.
--
-- So the server takes the later of the two: a client value that is already ahead
-- (an edit about to happen, or a clock set forward) is kept, and anything older
-- is replaced with the server's own `now()`. Arrival order is then the stamp
-- order, and a row written after a device's watermark is always greater than it.
create or replace function public.stamp_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = greatest(new.updated_at, now());
    return new;
end;
$$;

-- =============================================================================
-- profiles: the row every other table's foreign key needs
-- =============================================================================
-- `user_id` on places, people, memories, daily_entries, diary_notes and media is
-- a foreign key to public.profiles, so that row has to exist before the client
-- can write anything at all. Without it the first insert from any device fails
-- with "Key (user_id)=(...) is not present in table profiles" - for every table,
-- for every user - which is not a bug any client-side test can see, because it
-- needs a server with these constraints.
--
-- So the row is created here, when the auth user is created, which also covers a
-- user added from the dashboard or by any other client. The Android app does not
-- write to profiles on the normal path; it only fills in the display name it
-- knows, and does so idempotently, so an existing project that predates this
-- trigger still ends up with the row.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, email, display_name)
    values (
        new.id,
        coalesce(new.email, ''),
        coalesce(new.raw_user_meta_data ->> 'display_name', '')
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- =============================================================================
-- Row Level Security
-- =============================================================================
alter table public.profiles            enable row level security;
alter table public.places              enable row level security;
alter table public.people              enable row level security;
alter table public.memories            enable row level security;
alter table public.daily_entries       enable row level security;
alter table public.diary_notes         enable row level security;
alter table public.media               enable row level security;
alter table public.memory_person       enable row level security;
alter table public.memory_place        enable row level security;
alter table public.daily_entry_person  enable row level security;
alter table public.daily_entry_place   enable row level security;
alter table public.memory_shares       enable row level security;

-- Helper: is the caller the owner of this row?
create or replace function public.is_owner(owner uuid)
returns boolean
language sql
stable
as $$
    select owner = auth.uid();
$$;

-- Helper: has this memory been explicitly shared with the caller?
create or replace function public.is_shared_with_me(memory uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.memory_shares s
        where s.memory_id = memory
          and s.shared_with_user_id = auth.uid()
    );
$$;

-- profiles -------------------------------------------------------------------
drop policy if exists "profiles: read own" on public.profiles;
create policy "profiles: read own" on public.profiles
    for select using (public.is_owner(id));

drop policy if exists "profiles: insert own" on public.profiles;
create policy "profiles: insert own" on public.profiles
    for insert with check (public.is_owner(id));

drop policy if exists "profiles: update own" on public.profiles;
create policy "profiles: update own" on public.profiles
    for update using (public.is_owner(id));

drop policy if exists "profiles: delete own" on public.profiles;
create policy "profiles: delete own" on public.profiles
    for delete using (public.is_owner(id));

-- places / people ------------------------------------------------------------
drop policy if exists "places: owner only" on public.places;
create policy "places: owner only" on public.places
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

drop policy if exists "people: owner only" on public.people;
create policy "people: owner only" on public.people
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- memories -------------------------------------------------------------------
-- PRIVATE: owner only. SHARED: owner + a row in memory_shares. PUBLIC: readable
-- by any authenticated user, writable only by the owner.
drop policy if exists "memories: owner full access" on public.memories;
create policy "memories: owner full access" on public.memories
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

drop policy if exists "memories: shared read" on public.memories;
create policy "memories: shared read" on public.memories
    for select using (
        visibility = 'SHARED' and public.is_shared_with_me(id)
    );

drop policy if exists "memories: public read" on public.memories;
create policy "memories: public read" on public.memories
    for select using (
        visibility = 'PUBLIC'
        and deleted_at is null
        and auth.role() = 'authenticated'
    );

-- daily entries and diary notes: strictly private ----------------------------
-- The diary is never public. There is intentionally no PUBLIC path here.
drop policy if exists "daily_entries: owner only" on public.daily_entries;
create policy "daily_entries: owner only" on public.daily_entries
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

drop policy if exists "diary_notes: owner only" on public.diary_notes;
create policy "diary_notes: owner only" on public.diary_notes
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- media ----------------------------------------------------------------------
drop policy if exists "media: owner only" on public.media;
create policy "media: owner only" on public.media
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- link tables: access follows the parent memory or entry ---------------------
drop policy if exists "memory_person: read via memory" on public.memory_person;
create policy "memory_person: read via memory" on public.memory_person
    for select using (
        exists (
            select 1 from public.memories m
            where m.id = memory_person.memory_id
              and (public.is_owner(m.user_id) or (m.visibility = 'SHARED' and public.is_shared_with_me(m.id)))
        )
    );

drop policy if exists "memory_person: write as owner" on public.memory_person;
create policy "memory_person: write as owner" on public.memory_person
    for all using (
        exists (select 1 from public.memories m where m.id = memory_person.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_person.memory_id and public.is_owner(m.user_id))
    );

drop policy if exists "memory_place: read via memory" on public.memory_place;
create policy "memory_place: read via memory" on public.memory_place
    for select using (
        exists (
            select 1 from public.memories m
            where m.id = memory_place.memory_id
              and (public.is_owner(m.user_id) or (m.visibility = 'SHARED' and public.is_shared_with_me(m.id)))
        )
    );

drop policy if exists "memory_place: write as owner" on public.memory_place;
create policy "memory_place: write as owner" on public.memory_place
    for all using (
        exists (select 1 from public.memories m where m.id = memory_place.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_place.memory_id and public.is_owner(m.user_id))
    );

drop policy if exists "daily_entry_person: owner only" on public.daily_entry_person;
create policy "daily_entry_person: owner only" on public.daily_entry_person
    for all using (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_person.entry_id and public.is_owner(e.user_id))
    ) with check (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_person.entry_id and public.is_owner(e.user_id))
    );

drop policy if exists "daily_entry_place: owner only" on public.daily_entry_place;
create policy "daily_entry_place: owner only" on public.daily_entry_place
    for all using (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_place.entry_id and public.is_owner(e.user_id))
    ) with check (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_place.entry_id and public.is_owner(e.user_id))
    );

drop policy if exists "memory_shares: owner manages" on public.memory_shares;
create policy "memory_shares: owner manages" on public.memory_shares
    for all using (
        exists (select 1 from public.memories m where m.id = memory_shares.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_shares.memory_id and public.is_owner(m.user_id))
    );

drop policy if exists "memory_shares: recipient can see the grant" on public.memory_shares;
create policy "memory_shares: recipient can see the grant" on public.memory_shares
    for select using (shared_with_user_id = auth.uid());

-- =============================================================================
-- Storage policies
-- =============================================================================
-- Bucket layout: media/<user_id>/<ownerId>/<fileName>
-- The first path segment is always the caller's own user id, which is what the
-- policies below enforce.
insert into storage.buckets (id, name, public)
values ('media', 'media', false)
on conflict (id) do nothing;

insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do nothing;

drop policy if exists "media: owner reads own folder" on storage.objects;
create policy "media: owner reads own folder" on storage.objects
    for select using (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "media: owner writes own folder" on storage.objects;
create policy "media: owner writes own folder" on storage.objects
    for insert with check (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "media: owner deletes own folder" on storage.objects;
create policy "media: owner deletes own folder" on storage.objects
    for delete using (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "avatars: public read" on storage.objects;
create policy "avatars: public read" on storage.objects
    for select using (bucket_id = 'avatars');

drop policy if exists "avatars: owner writes own file" on storage.objects;
create policy "avatars: owner writes own file" on storage.objects
    for insert with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "avatars: owner deletes own file" on storage.objects;
create policy "avatars: owner deletes own file" on storage.objects
    for delete using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );


-- -----------------------------------------------------------------------------
-- One trigger per synchronised table. A table added to the client's sync tables
-- without one here would have a stamp that can fall behind a watermark, which is
-- silent data loss rather than an error, so it is also checked by a test.
-- -----------------------------------------------------------------------------
drop trigger if exists memories_stamp_updated_at on public.memories;
create trigger memories_stamp_updated_at before insert or update on public.memories
    for each row execute function public.stamp_updated_at();
drop trigger if exists daily_entries_stamp_updated_at on public.daily_entries;
create trigger daily_entries_stamp_updated_at before insert or update on public.daily_entries
    for each row execute function public.stamp_updated_at();
drop trigger if exists diary_notes_stamp_updated_at on public.diary_notes;
create trigger diary_notes_stamp_updated_at before insert or update on public.diary_notes
    for each row execute function public.stamp_updated_at();
drop trigger if exists people_stamp_updated_at on public.people;
create trigger people_stamp_updated_at before insert or update on public.people
    for each row execute function public.stamp_updated_at();
drop trigger if exists places_stamp_updated_at on public.places;
create trigger places_stamp_updated_at before insert or update on public.places
    for each row execute function public.stamp_updated_at();
drop trigger if exists media_stamp_updated_at on public.media;
create trigger media_stamp_updated_at before insert or update on public.media
    for each row execute function public.stamp_updated_at();

-- =============================================================================
-- Deletion, from the app
-- =============================================================================
-- Two functions, and the difference between them is what the user asked for.
--
-- Both are `security definer`, which is the point: the client holds only the
-- anon key and the user's own JWT, so it has no rights on `auth.users` at all.
-- A function that runs as its owner - the role that applied this file, which is
-- `postgres` in the SQL editor - can do what the client cannot, and both check
-- `auth.uid()` so they can only ever touch the caller's own account.
--
-- Neither one deletes anything in the storage buckets. `storage.objects` is not
-- a child of `auth.users`, so nothing cascades into it; the app deletes its
-- uploaded files first, while the rows holding their keys still exist.

-- Everything the account wrote, keeping the account itself.
create or replace function public.delete_my_data()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.profiles where id = auth.uid();
end;
$$;

-- The account as well: the profile row goes through the cascade above, and the
-- auth user goes here, which is what frees the email address for reuse.
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from auth.users where id = auth.uid();
end;
$$;

-- New functions are executable by PUBLIC by default; saying it explicitly is how
-- a reader knows the intent, and revoking first makes the grant true even if a
-- later migration changes the default.
--
-- `anon` is named separately, and that is not decoration: a Supabase project
-- grants EXECUTE on functions in `public` to `anon`, `authenticated` and
-- `service_role` through the platform's default privileges, so `revoke ... from
-- public` alone leaves the anon key - which travels inside every APK - able to
-- call these. `ci/check-schema.py` reproduces those default privileges and fails
-- when an anonymous caller can execute either function.
revoke all on function public.delete_my_data() from public, anon;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_data() to authenticated;
grant execute on function public.delete_my_account() to authenticated;

-- The two trigger functions are not called by anyone; PostgreSQL does not ask
-- for EXECUTE when a trigger fires. Refusing the key in the APK costs nothing.
revoke all on function public.handle_new_user() from public, anon;
revoke all on function public.stamp_updated_at() from public, anon;

-- The two helpers a policy calls are different: a policy's expression is
-- evaluated with the caller's privileges, so a role that can reach the table
-- needs EXECUTE on the function or the query errors instead of returning an
-- empty set. They only ever compare against `auth.uid()`, which is NULL for an
-- anonymous caller, so granting them is not a way in.
revoke all on function public.is_owner(uuid) from public;
revoke all on function public.is_shared_with_me(uuid) from public;
grant execute on function public.is_owner(uuid) to anon, authenticated;
grant execute on function public.is_shared_with_me(uuid) to anon, authenticated;
