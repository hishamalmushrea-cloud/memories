-- =============================================================================
-- The Supabase shapes `supabase/schema.sql` is written against
-- =============================================================================
-- `schema.sql` is a Supabase migration: it assumes the `auth` and `storage`
-- schemas exist, that `auth.uid()` reads the JWT claim, that `auth.users` is a
-- table a trigger can hang off, and that the `anon`/`authenticated` roles are
-- the ones RLS is written for. None of that is part of the file, so a project
-- that applies it has all of it already.
--
-- This file is the smallest faithful stand-in for those parts, and it exists so
-- the migration can be *run* somewhere instead of only read. It is applied to a
-- scratch database first, then `supabase/schema.sql` verbatim, then
-- `supabase/verify/10_checks.sql` asserts what the app depends on.
--
-- What it is not: a Supabase project. GoTrue's token handling, PostgREST's
-- request-to-session translation, and the storage service's own layer are not
-- here. What *is* here is the part that can be wrong in a schema - the tables,
-- the constraints, the policies, the triggers, the definer functions and the
-- grants - executed by a real PostgreSQL.
--
-- Applied as the database owner (in CI: the `postgres` superuser, which is also
-- who owns the objects in a real Supabase project).

-- The three roles Supabase gives a project, and the reason the policies mention
-- one of them: `anon` is an unauthenticated caller, `authenticated` has a JWT.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then
        create role anon nologin noinherit;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then
        create role authenticated nologin noinherit;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'service_role') then
        create role service_role nologin noinherit bypassrls;
    end if;
end
$$;

create schema if not exists auth;
create schema if not exists storage;

-- -----------------------------------------------------------------------------
-- auth.users
-- -----------------------------------------------------------------------------
-- Three columns are all `schema.sql` touches: the id the profile row is keyed
-- on, the email written into it, and the metadata the display name comes from.
create table if not exists auth.users (
    id                 uuid primary key default gen_random_uuid(),
    email              text unique,
    raw_user_meta_data jsonb not null default '{}'::jsonb,
    created_at         timestamptz not null default now()
);

-- What the JWT claim looks like from inside a policy or a definer function. The
-- claim is a session setting in Supabase too; the RLS tests below set it the way
-- PostgREST sets it, and clear it the way a signed-out request arrives.
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;

create or replace function auth.role()
returns text
language sql
stable
as $$
    select coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), 'anon');
$$;

create or replace function auth.jwt()
returns jsonb
language sql
stable
as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb);
$$;

-- -----------------------------------------------------------------------------
-- storage.buckets and storage.objects
-- -----------------------------------------------------------------------------
create table if not exists storage.buckets (
    id         text primary key,
    name       text not null,
    public     boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists storage.objects (
    id         uuid primary key default gen_random_uuid(),
    bucket_id  text not null references storage.buckets (id) on delete cascade,
    name       text not null,
    owner      uuid,
    created_at timestamptz not null default now()
);

alter table storage.objects enable row level security;

-- Supabase's own helper: the folder parts of a path, without the file name. The
-- policies read element one, so the shape matters - a path with no folder at all
-- yields an empty array and the policy denies it, exactly as it does on Supabase.
create or replace function storage.foldername(name text)
returns text[]
language sql
immutable
as $$
    select (string_to_array(name, '/'))[1:greatest(array_length(string_to_array(name, '/'), 1) - 1, 0)];
$$;

-- -----------------------------------------------------------------------------
-- Grants: the part that makes RLS the only thing protecting a row
-- -----------------------------------------------------------------------------
-- Supabase grants table access to both roles and relies on RLS for isolation, so
-- a policy that is missing or wrong is not masked by a privilege error. Default
-- privileges are set here, before `schema.sql` creates anything, so the tables it
-- creates are granted automatically.
grant usage on schema public, storage to anon, authenticated, service_role;
grant usage on schema auth to anon, authenticated, service_role;

grant all on all tables in schema public to anon, authenticated, service_role;
grant all on all sequences in schema public to anon, authenticated, service_role;
grant all on all functions in schema public to anon, authenticated, service_role;
grant all on all tables in schema storage to anon, authenticated, service_role;
grant all on all sequences in schema storage to anon, authenticated, service_role;

alter default privileges in schema public
    grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public
    grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public
    grant execute on functions to anon, authenticated, service_role;
alter default privileges in schema storage
    grant all on tables to anon, authenticated, service_role;
alter default privileges in schema storage
    grant all on sequences to anon, authenticated, service_role;

-- `auth.users` itself is not a table a client may read; the deletion function is
-- the only path to it, and it runs as its owner. `select` is granted to the two
-- client roles all the same, because the deletion check has to be able to look at
-- what a deletion did without leaving the role the statement switched to.
revoke all on auth.users from public;
grant select on auth.users to anon, authenticated, service_role;
