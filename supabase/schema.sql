-- =============================================================================
-- Memory Map — Supabase schema and Row Level Security
--
-- Status: written in Phase 1 so the data contract is fixed before the client
-- code grows, and applied in Phase 9 when authentication lands.
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
create type visibility_level as enum ('PRIVATE', 'SHARED', 'PUBLIC');

create type emotion_level as enum ('HAPPY', 'SAD', 'LOVE', 'FEAR', 'PRIDE', 'NOSTALGIA');

create type sync_state as enum (
    'PENDING_CREATE',
    'PENDING_UPDATE',
    'PENDING_DELETE',
    'SYNCED',
    'SYNC_ERROR'
);

create type media_kind as enum ('PHOTO', 'AUDIO', 'VIDEO');

-- -----------------------------------------------------------------------------
-- profiles: one row per auth user
-- -----------------------------------------------------------------------------
create table public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    email        text not null,
    display_name text not null default '',
    avatar_url   text,
    created_at   timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- places and people: the two linkable nouns
-- -----------------------------------------------------------------------------
create table public.places (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles (id) on delete cascade,
    name       text not null,
    latitude   double precision not null,
    longitude  double precision not null,
    created_at timestamptz not null default now(),
    unique (user_id, name)
);

create table public.people (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles (id) on delete cascade,
    name       text not null,
    created_at timestamptz not null default now(),
    unique (user_id, name)
);

-- -----------------------------------------------------------------------------
-- memories
-- -----------------------------------------------------------------------------
create table public.memories (
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

create index memories_user_date_idx on public.memories (user_id, memory_date desc);
create index memories_location_idx  on public.memories (user_id, latitude, longitude)
    where latitude is not null and longitude is not null;

-- -----------------------------------------------------------------------------
-- daily entries: the day is the unit, the event is what happens inside it
-- -----------------------------------------------------------------------------
create table public.daily_entries (
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

create index daily_entries_user_date_idx on public.daily_entries (user_id, entry_date desc, entry_time asc);

-- -----------------------------------------------------------------------------
-- diary notes: the free-form "what did I do today?" text, one per user per day
-- -----------------------------------------------------------------------------
create table public.diary_notes (
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
create table public.media (
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
    sync_status    sync_state not null default 'PENDING_CREATE',
    last_synced_at timestamptz
);

create index media_owner_idx on public.media (owner_type, owner_id);
create index media_user_idx  on public.media (user_id);

-- -----------------------------------------------------------------------------
-- links
-- -----------------------------------------------------------------------------
create table public.memory_person (
    memory_id uuid not null references public.memories (id) on delete cascade,
    person_id uuid not null references public.people (id) on delete cascade,
    primary key (memory_id, person_id)
);

create table public.memory_place (
    memory_id uuid not null references public.memories (id) on delete cascade,
    place_id  uuid not null references public.places (id) on delete cascade,
    primary key (memory_id, place_id)
);

create table public.daily_entry_person (
    entry_id  uuid not null references public.daily_entries (id) on delete cascade,
    person_id uuid not null references public.people (id) on delete cascade,
    primary key (entry_id, person_id)
);

create table public.daily_entry_place (
    entry_id uuid not null references public.daily_entries (id) on delete cascade,
    place_id uuid not null references public.places (id) on delete cascade,
    primary key (entry_id, place_id)
);

create table public.memory_shares (
    memory_id           uuid not null references public.memories (id) on delete cascade,
    shared_with_user_id uuid not null references public.profiles (id) on delete cascade,
    role                text not null default 'viewer',
    created_at          timestamptz not null default now(),
    primary key (memory_id, shared_with_user_id)
);

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
create policy "profiles: read own" on public.profiles
    for select using (public.is_owner(id));

create policy "profiles: insert own" on public.profiles
    for insert with check (public.is_owner(id));

create policy "profiles: update own" on public.profiles
    for update using (public.is_owner(id));

create policy "profiles: delete own" on public.profiles
    for delete using (public.is_owner(id));

-- places / people ------------------------------------------------------------
create policy "places: owner only" on public.places
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

create policy "people: owner only" on public.people
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- memories -------------------------------------------------------------------
-- PRIVATE: owner only. SHARED: owner + a row in memory_shares. PUBLIC: readable
-- by any authenticated user, writable only by the owner.
create policy "memories: owner full access" on public.memories
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

create policy "memories: shared read" on public.memories
    for select using (
        visibility = 'SHARED' and public.is_shared_with_me(id)
    );

create policy "memories: public read" on public.memories
    for select using (
        visibility = 'PUBLIC'
        and deleted_at is null
        and auth.role() = 'authenticated'
    );

-- daily entries and diary notes: strictly private ----------------------------
-- The diary is never public. There is intentionally no PUBLIC path here.
create policy "daily_entries: owner only" on public.daily_entries
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

create policy "diary_notes: owner only" on public.diary_notes
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- media ----------------------------------------------------------------------
create policy "media: owner only" on public.media
    for all using (public.is_owner(user_id)) with check (public.is_owner(user_id));

-- link tables: access follows the parent memory or entry ---------------------
create policy "memory_person: read via memory" on public.memory_person
    for select using (
        exists (
            select 1 from public.memories m
            where m.id = memory_person.memory_id
              and (public.is_owner(m.user_id) or (m.visibility = 'SHARED' and public.is_shared_with_me(m.id)))
        )
    );

create policy "memory_person: write as owner" on public.memory_person
    for all using (
        exists (select 1 from public.memories m where m.id = memory_person.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_person.memory_id and public.is_owner(m.user_id))
    );

create policy "memory_place: read via memory" on public.memory_place
    for select using (
        exists (
            select 1 from public.memories m
            where m.id = memory_place.memory_id
              and (public.is_owner(m.user_id) or (m.visibility = 'SHARED' and public.is_shared_with_me(m.id)))
        )
    );

create policy "memory_place: write as owner" on public.memory_place
    for all using (
        exists (select 1 from public.memories m where m.id = memory_place.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_place.memory_id and public.is_owner(m.user_id))
    );

create policy "daily_entry_person: owner only" on public.daily_entry_person
    for all using (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_person.entry_id and public.is_owner(e.user_id))
    ) with check (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_person.entry_id and public.is_owner(e.user_id))
    );

create policy "daily_entry_place: owner only" on public.daily_entry_place
    for all using (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_place.entry_id and public.is_owner(e.user_id))
    ) with check (
        exists (select 1 from public.daily_entries e where e.id = daily_entry_place.entry_id and public.is_owner(e.user_id))
    );

create policy "memory_shares: owner manages" on public.memory_shares
    for all using (
        exists (select 1 from public.memories m where m.id = memory_shares.memory_id and public.is_owner(m.user_id))
    ) with check (
        exists (select 1 from public.memories m where m.id = memory_shares.memory_id and public.is_owner(m.user_id))
    );

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

create policy "media: owner reads own folder" on storage.objects
    for select using (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy "media: owner writes own folder" on storage.objects
    for insert with check (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy "media: owner deletes own folder" on storage.objects
    for delete using (
        bucket_id = 'media'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

create policy "avatars: public read" on storage.objects
    for select using (bucket_id = 'avatars');

create policy "avatars: owner writes own file" on storage.objects
    for insert with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- =============================================================================
-- Account deletion
-- =============================================================================
-- Deleting the auth user cascades through profiles and removes every row and,
-- through the storage policies above, leaves the owner able to purge their
-- files first from the in-app "delete my data" flow.
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
