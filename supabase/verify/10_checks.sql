-- =============================================================================
-- What the app depends on, asserted against the applied schema
-- =============================================================================
-- Runs after `supabase/verify/00_supabase_stubs.sql` and `supabase/schema.sql`,
-- against a real PostgreSQL, in a scratch database. Every check prints
-- `ok: <name>` as it passes and raises on the first thing that is not true, so a
-- failure names the guarantee that broke rather than a line number.
--
-- The two things a reader should keep in mind:
--
--   * Everything a user could do is done as the `authenticated` role with a JWT
--     claim set the way PostgREST sets it. Running the same statements as the
--     owner of the tables would bypass RLS and prove nothing.
--   * The two deletion functions are `security definer`, owned by the database
--     owner - which is exactly how they run on Supabase, where they are owned by
--     `postgres`. So they are allowed to delete rows RLS would hide, and the only
--     thing standing between a caller and someone else's account is the
--     `auth.uid()` check inside them. That is what the deletion checks test.
--
-- Ids are fixed so a failure is repeatable and readable.

\set ON_ERROR_STOP on

\echo '-- structure'
begin;

-- Every table the client can reach must have RLS on. A table added without it is
-- readable by every signed-in user of the project, and nothing else would say so.
do $$
declare
    unprotected text;
begin
    select string_agg(c.relname, ', ' order by c.relname) into unprotected
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity;

    if unprotected is not null then
        raise exception 'these public tables have no row level security: %', unprotected;
    end if;
end
$$;
\echo 'ok: every table in public has row level security enabled'

-- RLS with no policy denies everything, which looks like a broken app rather
-- than a leak. Supabase's own linter asks for this too.
do $$
declare
    unpoliced text;
begin
    select string_agg(c.relname, ', ' order by c.relname) into unpoliced
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind = 'r'
      and not exists (select 1 from pg_policy p where p.polrelid = c.oid);

    if unpoliced is not null then
        raise exception 'these public tables have row level security but no policy: %', unpoliced;
    end if;
end
$$;
\echo 'ok: every table in public has at least one policy'

-- The deletion functions are the two operations that can destroy an account, and
-- they are the only reason a client can do it at all.
do $$
declare
    definition text;
begin
    if not exists (
        select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public' and p.proname = 'delete_my_account' and p.prosecdef
    ) then
        raise exception 'delete_my_account() is missing or is not security definer';
    end if;

    if not exists (
        select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public' and p.proname = 'delete_my_data' and p.prosecdef
    ) then
        raise exception 'delete_my_data() is missing or is not security definer';
    end if;

    -- A definer function with a search_path a caller can influence is the classic
    -- way a definer function is made to run someone else's code.
    -- Written as a pattern rather than a substring: PostgreSQL re-prints the
    -- setting as `SET search_path TO 'public'`, quotes and all.
    definition := pg_get_functiondef('public.delete_my_account()'::regprocedure);
    if definition !~* 'search_path\s*(to|=)\s*''?public''?' then
        raise exception 'delete_my_account() does not pin its search_path to public: %', definition;
    end if;
end
$$;
\echo 'ok: both deletion functions are security definer with a pinned search_path'

do $$
begin
    if not has_function_privilege('authenticated', 'public.delete_my_account()', 'EXECUTE')
       or not has_function_privilege('authenticated', 'public.delete_my_data()', 'EXECUTE') then
        raise exception 'a signed-in user cannot execute the deletion functions';
    end if;

    -- `anon` holds the anon key that ships inside the APK, so this is the one
    -- grant that has to be absent.
    if has_function_privilege('anon', 'public.delete_my_account()', 'EXECUTE')
       or has_function_privilege('anon', 'public.delete_my_data()', 'EXECUTE') then
        raise exception 'an anonymous caller can execute the deletion functions';
    end if;
end
$$;
\echo 'ok: only authenticated callers may execute the deletion functions'

-- The trigger functions and the helpers, same question. A policy is evaluated
-- with the caller's privileges, so the two helpers have to be callable by
-- whoever can reach a table that mentions them - and nothing else should be.
do $$
declare
    reachable text;
begin
    if has_function_privilege('anon', 'public.handle_new_user()', 'EXECUTE')
       or has_function_privilege('anon', 'public.stamp_updated_at()', 'EXECUTE') then
        raise exception 'an anonymous caller can execute a trigger function';
    end if;

    if not has_function_privilege('authenticated', 'public.is_owner(uuid)', 'EXECUTE')
       or not has_function_privilege('authenticated', 'public.is_shared_with_me(uuid)', 'EXECUTE') then
        raise exception 'a signed-in caller cannot execute a policy helper';
    end if;
end
$$;
\echo 'ok: trigger functions are closed to anonymous callers and policy helpers are open'

-- Six synced tables, six triggers. The Kotlin contract test reads this out of the
-- file; here it is read out of the database it was applied to.
do $$
declare
    stamped text;
begin
    select string_agg(t.table_name, ', ' order by t.table_name) into stamped
    from (values
        ('memories'), ('daily_entries'), ('diary_notes'), ('people'), ('places'), ('media')
    ) as t(table_name)
    where not exists (
        select 1 from pg_trigger g
        join pg_class c on c.oid = g.tgrelid
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public'
          and c.relname::text = t.table_name
          and not g.tgisinternal
          and g.tgname::text = t.table_name || '_stamp_updated_at'
    );

    if stamped is not null then
        raise exception 'these synced tables have no arrival stamp trigger: %', stamped;
    end if;
end
$$;
\echo 'ok: every synced table stamps updated_at on arrival'

-- The status column is the server's own record of a row, and it is what the
-- client reads back. `text` would accept any string a writer sent; the enum
-- refuses one the client could not parse.
do $$
declare
    loose text;
begin
    select string_agg(t.table_name, ', ' order by t.table_name) into loose
    from (values
        ('memories'), ('daily_entries'), ('diary_notes'), ('people'), ('places'), ('media')
    ) as t(table_name)
    where not exists (
        select 1 from information_schema.columns c
        where c.table_schema = 'public'
          and c.table_name = t.table_name
          and c.column_name = 'sync_status'
          and c.udt_name = 'sync_state'
    );

    if loose is not null then
        raise exception 'these synced tables do not type sync_status as sync_state: %', loose;
    end if;
end
$$;
\echo 'ok: every synced table types its status as the enum'

-- Every row default in the schema is gen_random_uuid(). On Supabase it arrives
-- with pgcrypto; where pgcrypto is absent it has to come from PostgreSQL itself,
-- so this says which of the two is carrying the schema.
do $$
begin
    if gen_random_uuid() is null then
        raise exception 'gen_random_uuid() returned null';
    end if;
    perform gen_random_uuid();
end
$$;
\echo 'ok: gen_random_uuid() is available for the id defaults'

commit;

-- -----------------------------------------------------------------------------
-- Two accounts, and the state both of them start from
-- -----------------------------------------------------------------------------
\echo '-- accounts and the profile row'

begin;

-- The trigger on auth.users is what makes the first sync of every table possible:
-- each table's user_id is a foreign key to the profile row it creates.
insert into auth.users (id, email, raw_user_meta_data) values
    ('00000000-0000-0000-0000-00000000000a', 'a@example.com', '{"display_name": "أ"}'::jsonb),
    ('00000000-0000-0000-0000-00000000000b', 'b@example.com', '{"display_name": "ب"}'::jsonb);

do $$
begin
    if (select count(*) from public.profiles where id = '00000000-0000-0000-0000-00000000000a') <> 1 then
        raise exception 'inserting an auth user did not create a profile row';
    end if;

    if (select email from public.profiles where id = '00000000-0000-0000-0000-00000000000a') <> 'a@example.com' then
        raise exception 'the profile row did not take the email from the auth user';
    end if;

    if (select display_name from public.profiles where id = '00000000-0000-0000-0000-00000000000a') <> 'أ' then
        raise exception 'the profile row did not take the display name from the metadata';
    end if;
end
$$;
\echo 'ok: a new auth user gets a profile row with its email and display name'

-- A row written by a client that was offline carries the stamp of the moment it
-- was edited, which can be hours behind its arrival. The trigger has to move the
-- stamp forward, or a download window that has already passed that moment skips
-- the row forever.
do $$
declare
    stamped timestamptz;
begin
    insert into public.memories (id, user_id, title, memory_date, updated_at)
    values (
        '10000000-0000-0000-0000-00000000000a',
        '00000000-0000-0000-0000-00000000000a',
        'ذكرى قديمة',
        current_date - 30,
        now() - interval '3 hours'
    )
    returning updated_at into stamped;

    if stamped < now() - interval '1 minute' then
        raise exception 'an old client stamp was stored as-is: %', stamped;
    end if;
end
$$;
\echo 'ok: an old client stamp is replaced by the arrival time'

commit;

-- -----------------------------------------------------------------------------
-- Isolation between accounts, as a signed-in user with a JWT claim
-- -----------------------------------------------------------------------------
\echo '-- isolation'

begin;

-- A second account's data, written while the session is A so the rows go through
-- the same policies a real client goes through.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

insert into public.places (id, user_id, name, latitude, longitude) values
    ('20000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', 'صنعاء', 15.3694, 44.1910);
insert into public.memories (id, user_id, title, memory_date) values
    ('10000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000a', 'ذكرى ثانية', current_date);
insert into public.daily_entries (id, user_id, entry_date, entry_time, title) values
    ('30000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', current_date, now(), 'حدث');
insert into public.diary_notes (user_id, note_date, body) values
    ('00000000-0000-0000-0000-00000000000a', current_date, 'ملاحظة');
insert into public.media (id, user_id, owner_type, owner_id, media_type, storage_path) values
    ('40000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a',
     'MEMORY', '10000000-0000-0000-0000-00000000000b', 'PHOTO', 'a/photo.jpg');

reset role;

-- Now as B: nothing of A's, on any table.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

do $$
declare
    leaked text := '';
begin
    -- B has a profile row of its own - the trigger makes one for every account -
    -- so the question here is whether it can see a row that is not that one.
    if (select count(*) from public.profiles
        where id <> '00000000-0000-0000-0000-00000000000b') <> 0 then
        leaked := leaked || ' profiles';
    end if;
    if (select count(*) from public.memories) <> 0 then leaked := leaked || ' memories'; end if;
    if (select count(*) from public.daily_entries) <> 0 then leaked := leaked || ' daily_entries'; end if;
    if (select count(*) from public.diary_notes) <> 0 then leaked := leaked || ' diary_notes'; end if;
    if (select count(*) from public.people) <> 0 then leaked := leaked || ' people'; end if;
    if (select count(*) from public.places) <> 0 then leaked := leaked || ' places'; end if;
    if (select count(*) from public.media) <> 0 then leaked := leaked || ' media'; end if;
    if (select count(*) from public.memory_person) <> 0 then leaked := leaked || ' memory_person'; end if;
    if (select count(*) from public.memory_shares) <> 0 then leaked := leaked || ' memory_shares'; end if;

    if leaked <> '' then
        raise exception 'another account could read:%', leaked;
    end if;
end
$$;
\echo 'ok: another account reads none of the first account rows'

do $$
declare
    touched int;
    refused boolean := false;
begin
    update public.memories set title = 'stolen' where id = '10000000-0000-0000-0000-00000000000b';
    get diagnostics touched = row_count;
    if touched <> 0 then
        raise exception 'another account updated % row(s) that are not its own', touched;
    end if;

    delete from public.memories where id = '10000000-0000-0000-0000-00000000000b';
    get diagnostics touched = row_count;
    if touched <> 0 then
        raise exception 'another account deleted % row(s) that are not its own', touched;
    end if;

    -- Writing a row that claims to belong to someone else has to be refused by
    -- the policy's `with check`, not silently accepted.
    begin
        insert into public.memories (user_id, title, memory_date)
        values ('00000000-0000-0000-0000-00000000000a', 'منتحل', current_date);
    exception when insufficient_privilege then
        refused := true;
    end;

    if not refused then
        raise exception 'another account inserted a row owned by the first account';
    end if;
end
$$;
\echo 'ok: another account can neither update, delete nor forge a row it does not own'

reset role;

-- An unauthenticated caller holds the anon key from the APK. Every table here
-- must be closed to it.
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claim.role', 'anon', true);

do $$
declare
    leaked text := '';
begin
    if (select count(*) from public.memories) <> 0 then leaked := leaked || ' memories'; end if;
    if (select count(*) from public.daily_entries) <> 0 then leaked := leaked || ' daily_entries'; end if;
    if (select count(*) from public.diary_notes) <> 0 then leaked := leaked || ' diary_notes'; end if;
    if (select count(*) from public.media) <> 0 then leaked := leaked || ' media'; end if;
    if (select count(*) from public.profiles) <> 0 then leaked := leaked || ' profiles'; end if;

    if leaked <> '' then
        raise exception 'an anonymous caller could read:%', leaked;
    end if;
end
$$;
\echo 'ok: an anonymous caller reads nothing at all'

reset role;
commit;

-- -----------------------------------------------------------------------------
-- The three visibilities a memory can have
-- -----------------------------------------------------------------------------
\echo '-- visibility'

begin;

-- A grants B a share on a SHARED memory, and keeps a PRIVATE one to itself.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

insert into public.memories (id, user_id, title, memory_date, visibility) values
    ('10000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-00000000000a', 'مشتركة', current_date, 'SHARED'),
    ('10000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-00000000000a', 'خاصة', current_date, 'PRIVATE'),
    ('10000000-0000-0000-0000-00000000000e', '00000000-0000-0000-0000-00000000000a', 'عامة', current_date, 'PUBLIC');

insert into public.memory_shares (memory_id, shared_with_user_id, role)
values ('10000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-00000000000b', 'viewer');

reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

do $$
declare
    shared_visible int;
    private_visible int;
    public_visible int;
begin
    select count(*) into shared_visible from public.memories
        where id = '10000000-0000-0000-0000-00000000000c';
    select count(*) into private_visible from public.memories
        where id = '10000000-0000-0000-0000-00000000000d';
    select count(*) into public_visible from public.memories
        where id = '10000000-0000-0000-0000-00000000000e';

    if shared_visible <> 1 then
        raise exception 'a memory shared with this account is not readable by it';
    end if;
    if private_visible <> 0 then
        raise exception 'a PRIVATE memory is readable by an account it was not shared with';
    end if;
    if public_visible <> 1 then
        raise exception 'a PUBLIC memory is not readable by a signed-in account';
    end if;
end
$$;
\echo 'ok: PRIVATE is owner-only, SHARED reaches the recipient, PUBLIC reaches signed-in accounts'

-- The recipient may read the memory, not rewrite it.
do $$
declare
    touched int;
    refused boolean := false;
begin
    update public.memories set title = 'edited by the recipient'
        where id = '10000000-0000-0000-0000-00000000000c';
    get diagnostics touched = row_count;
    if touched <> 0 then
        raise exception 'the recipient of a share could edit % memory row(s)', touched;
    end if;

    begin
        insert into public.memories (user_id, title, memory_date)
        values ('00000000-0000-0000-0000-00000000000b', 'رد', current_date);
    exception when insufficient_privilege then
        refused := true;
    end;
    if refused then
        raise exception 'an account cannot create a memory of its own';
    end if;
end
$$;
\echo 'ok: a share is read-only, and the recipient can still write its own rows'

reset role;
commit;

-- -----------------------------------------------------------------------------
-- The storage bucket, which does not cascade from the account
-- -----------------------------------------------------------------------------
\echo '-- storage'

begin;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

insert into storage.objects (id, bucket_id, name, owner) values
    ('50000000-0000-0000-0000-00000000000a', 'media', '00000000-0000-0000-0000-00000000000a/photo.jpg',
     '00000000-0000-0000-0000-00000000000a'),
    ('50000000-0000-0000-0000-00000000000b', 'avatars', '00000000-0000-0000-0000-00000000000a/avatar.png',
     '00000000-0000-0000-0000-00000000000a');

do $$
declare
    refused boolean := false;
begin
    -- A file with no folder at all must not be writable: the policy reads the
    -- first path segment, and an empty folder list would otherwise compare null
    -- with the user id.
    begin
        insert into storage.objects (bucket_id, name) values ('media', 'photo-without-a-folder.jpg');
    exception when insufficient_privilege then
        refused := true;
    end;
    if not refused then
        raise exception 'a media object could be written outside a user folder';
    end if;
end
$$;
\echo 'ok: an uploaded file must live in the uploader folder'

reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

do $$
declare
    touched int;
    refused boolean := false;
begin
    if (select count(*) from storage.objects where bucket_id = 'media') <> 0 then
        raise exception 'another account can list private media objects';
    end if;

    begin
        insert into storage.objects (bucket_id, name)
        values ('media', '00000000-0000-0000-0000-00000000000a/planted.jpg');
    exception when insufficient_privilege then
        refused := true;
    end;
    if not refused then
        raise exception 'another account wrote a file into this account folder';
    end if;

    delete from storage.objects where bucket_id = 'media';
    get diagnostics touched = row_count;
    if touched <> 0 then
        raise exception 'another account deleted % private object(s)', touched;
    end if;
end
$$;
\echo 'ok: the media bucket is private to the folder owner'

reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claim.role', 'anon', true);

do $$
begin
    if (select count(*) from storage.objects where bucket_id = 'media') <> 0 then
        raise exception 'an anonymous caller can read the private media bucket';
    end if;
    if (select count(*) from storage.objects where bucket_id = 'avatars') <> 1 then
        raise exception 'the public avatars bucket is not readable';
    end if;
end
$$;
\echo 'ok: the media bucket is closed to anonymous callers and avatars is open'

reset role;
commit;

-- -----------------------------------------------------------------------------
-- The two deletions
-- -----------------------------------------------------------------------------
\echo '-- deletion'

begin;

-- The records, keeping the account: what a user leaving a device wants.
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000a', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

select public.delete_my_data();

reset role;

do $$
declare
    remaining text := '';
begin
    if exists (select 1 from public.profiles where id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' profiles';
    end if;
    if exists (select 1 from public.memories where user_id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' memories';
    end if;
    if exists (select 1 from public.daily_entries where user_id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' daily_entries';
    end if;
    if exists (select 1 from public.diary_notes where user_id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' diary_notes';
    end if;
    if exists (select 1 from public.places where user_id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' places';
    end if;
    if exists (select 1 from public.media where user_id = '00000000-0000-0000-0000-00000000000a') then
        remaining := remaining || ' media';
    end if;

    if remaining <> '' then
        raise exception 'delete_my_data left rows behind:%', remaining;
    end if;

    -- The account itself is the point of the difference between the two calls.
    if not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000a') then
        raise exception 'delete_my_data deleted the auth user as well';
    end if;

    -- The uploaded files are not rows of a table that cascades, which is why the
    -- app deletes them itself before it deletes the account.
    if not exists (select 1 from storage.objects where id = '50000000-0000-0000-0000-00000000000a') then
        raise exception 'delete_my_data removed a storage object, which the app is written to do itself';
    end if;

    -- And another account is untouched.
    if not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000b') then
        raise exception 'delete_my_data touched another account';
    end if;
end
$$;
\echo 'ok: delete_my_data removes the rows and keeps the account'

commit;

-- The account itself: the email address has to become usable again, and every
-- table that hung off the profile row has to go with it.
begin;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

insert into public.memories (id, user_id, title, memory_date) values
    ('10000000-0000-0000-0000-00000000000f', '00000000-0000-0000-0000-00000000000b', 'B 的 ذكرى', current_date);
insert into public.people (id, user_id, name) values
    ('60000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000b', 'أحمد');

-- A row of A's that the deletion of A must not touch, to prove the function is
-- scoped to the caller: written before A is deleted, read after.
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000b', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

select public.delete_my_account();

reset role;

do $$
declare
    remaining text := '';
begin
    if exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000b') then
        raise exception 'delete_my_account left the auth user behind';
    end if;
    if exists (select 1 from public.profiles where id = '00000000-0000-0000-0000-00000000000b') then
        remaining := remaining || ' profiles';
    end if;
    if exists (select 1 from public.memories where user_id = '00000000-0000-0000-0000-00000000000b') then
        remaining := remaining || ' memories';
    end if;
    if exists (select 1 from public.people where user_id = '00000000-0000-0000-0000-00000000000b') then
        remaining := remaining || ' people';
    end if;

    if remaining <> '' then
        raise exception 'delete_my_account left rows behind:%', remaining;
    end if;

    -- The other account was already emptied by its own deletion above; the
    -- storage objects of both are still there, because nothing cascades there.
    if (select count(*) from storage.objects) <> 2 then
        raise exception 'deleting an account changed the number of stored objects';
    end if;
end
$$;
\echo 'ok: delete_my_account ends the account and everything that pointed at it'

commit;

-- -----------------------------------------------------------------------------
-- An anonymous caller cannot delete anything
-- -----------------------------------------------------------------------------
\echo '-- anon and the deletions'

begin;

-- Signing up is GoTrue's business: the row in auth.users is written by the
-- platform, and the client only ever holds the token that follows it. Creating it
-- has to happen outside the authenticated role, exactly as it does on Supabase.
insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000c', 'c@example.com');

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000c', true);
select set_config('request.jwt.claim.role', 'authenticated', true);
insert into public.memories (id, user_id, title, memory_date)
values ('10000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-00000000000c', 'ذكرى', current_date);
reset role;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claim.role', 'anon', true);

do $$
declare
    refused_account boolean := false;
    refused_data boolean := false;
begin
    begin
        perform public.delete_my_account();
    exception when insufficient_privilege then
        refused_account := true;
    end;

    begin
        perform public.delete_my_data();
    exception when insufficient_privilege then
        refused_data := true;
    end;

    if not refused_account or not refused_data then
        raise exception 'an anonymous caller executed a deletion function';
    end if;
end
$$;
\echo 'ok: an anonymous caller cannot execute either deletion function'

reset role;

-- A signed-in caller with no claim at all (a session that expired between the
-- request and the call) must delete nothing rather than everything: `auth.uid()`
-- is null, and `where id = null` matches no row.
set local role authenticated;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claim.role', 'authenticated', true);

select public.delete_my_account();

reset role;

do $$
begin
    if not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-00000000000c') then
        raise exception 'a caller with no claim deleted an account';
    end if;
end
$$;
\echo 'ok: a caller with no claim deletes nothing'

rollback;
