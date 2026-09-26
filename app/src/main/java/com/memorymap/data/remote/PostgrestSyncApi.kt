package com.memorymap.data.remote

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to Postgres through Supabase's Postgrest plugin, as the signed-in user.
 *
 * Row Level Security does the authorisation: every query is implicitly scoped to
 * the caller by `supabase/schema.sql`, so a bug here cannot read somebody else's
 * archive. Only the anon key is ever used; the client carries the user's JWT.
 */
@Singleton
class PostgrestSyncApi @Inject constructor(
    private val provider: SupabaseClientProvider,
) : SyncApi {

    override suspend fun upsertMemories(rows: List<MemoryRecord>) {
        if (rows.isEmpty()) return
        table(TABLE_MEMORIES).upsert(rows)
    }

    override suspend fun upsertEntries(rows: List<EntryRecord>) {
        if (rows.isEmpty()) return
        table(TABLE_ENTRIES).upsert(rows)
    }

    override suspend fun fetchMemories(userId: String, since: String?): List<MemoryRecord> =
        table(TABLE_MEMORIES)
            .select {
                filter {
                    eq("user_id", userId)
                    // No watermark means a first sync, which reads everything the
                    // user owns. RLS keeps that to their own rows.
                    if (since != null) gt("updated_at", since)
                }
            }
            .decodeList()

    override suspend fun fetchEntries(userId: String, since: String?): List<EntryRecord> =
        table(TABLE_ENTRIES)
            .select {
                filter {
                    eq("user_id", userId)
                    if (since != null) gt("updated_at", since)
                }
            }
            .decodeList()

    override suspend fun upsertMedia(rows: List<MediaRecord>) {
        if (rows.isEmpty()) return
        table(TABLE_MEDIA).upsert(rows)
    }

    override suspend fun fetchMedia(userId: String, since: String?): List<MediaRecord> =
        table(TABLE_MEDIA)
            .select {
                filter {
                    eq("user_id", userId)
                    if (since != null) gt("updated_at", since)
                }
            }
            .decodeList()

    override suspend fun upsertPeople(rows: List<PersonRecord>) {
        if (rows.isEmpty()) return
        table(TABLE_PEOPLE).upsert(rows)
    }

    override suspend fun upsertPlaces(rows: List<PlaceRecord>) {
        if (rows.isEmpty()) return
        table(TABLE_PLACES).upsert(rows)
    }

    override suspend fun fetchPeople(userId: String, since: String?): List<PersonRecord> =
        table(TABLE_PEOPLE)
            .select {
                filter {
                    eq("user_id", userId)
                    if (since != null) gt("updated_at", since)
                }
            }
            .decodeList()

    override suspend fun fetchPlaces(userId: String, since: String?): List<PlaceRecord> =
        table(TABLE_PLACES)
            .select {
                filter {
                    eq("user_id", userId)
                    if (since != null) gt("updated_at", since)
                }
            }
            .decodeList()

    override suspend fun replaceMemoryPeople(memoryIds: List<String>, links: List<MemoryPersonLink>) {
        replace(TABLE_MEMORY_PERSON, "memory_id", memoryIds, links)
    }

    override suspend fun replaceMemoryPlaces(memoryIds: List<String>, links: List<MemoryPlaceLink>) {
        replace(TABLE_MEMORY_PLACE, "memory_id", memoryIds, links)
    }

    override suspend fun replaceEntryPeople(entryIds: List<String>, links: List<EntryPersonLink>) {
        replace(TABLE_ENTRY_PERSON, "entry_id", entryIds, links)
    }

    override suspend fun replaceEntryPlaces(entryIds: List<String>, links: List<EntryPlaceLink>) {
        replace(TABLE_ENTRY_PLACE, "entry_id", entryIds, links)
    }

    override suspend fun fetchMemoryPeople(memoryIds: List<String>): List<MemoryPersonLink> =
        fetchLinks(TABLE_MEMORY_PERSON, "memory_id", memoryIds)

    override suspend fun fetchMemoryPlaces(memoryIds: List<String>): List<MemoryPlaceLink> =
        fetchLinks(TABLE_MEMORY_PLACE, "memory_id", memoryIds)

    override suspend fun fetchEntryPeople(entryIds: List<String>): List<EntryPersonLink> =
        fetchLinks(TABLE_ENTRY_PERSON, "entry_id", entryIds)

    override suspend fun fetchEntryPlaces(entryIds: List<String>): List<EntryPlaceLink> =
        fetchLinks(TABLE_ENTRY_PLACE, "entry_id", entryIds)

    /**
     * Deletes the old links of these records and writes the new set.
     *
     * Done in that order because the link tables have a composite primary key:
     * inserting first would collide with the row it is meant to replace. An
     * empty new set is a real state, not a no-op - it is how an unlink is sent.
     */
    private suspend inline fun <reified T : Any> replace(
        table: String,
        ownerColumn: String,
        ownerIds: List<String>,
        links: List<T>,
    ) {
        if (ownerIds.isEmpty()) return
        table(table).delete {
            filter { isIn(ownerColumn, ownerIds) }
        }
        if (links.isEmpty()) return
        table(table).upsert(links)
    }

    private suspend inline fun <reified T : Any> fetchLinks(
        table: String,
        ownerColumn: String,
        ownerIds: List<String>,
    ): List<T> {
        if (ownerIds.isEmpty()) return emptyList()
        return table(table)
            .select {
                filter { isIn(ownerColumn, ownerIds) }
            }
            .decodeList()
    }

    private fun table(name: String): PostgrestQueryBuilder {
        val client = provider.get()
            ?: throw IllegalStateException("Cloud sync is not configured on this device")
        return client.postgrest.from(name)
    }

    private companion object {
        const val TABLE_MEMORIES = "memories"
        const val TABLE_ENTRIES = "daily_entries"
        const val TABLE_PEOPLE = "people"
        const val TABLE_PLACES = "places"
        const val TABLE_MEMORY_PERSON = "memory_person"
        const val TABLE_MEMORY_PLACE = "memory_place"
        const val TABLE_ENTRY_PERSON = "daily_entry_person"
        const val TABLE_ENTRY_PLACE = "daily_entry_place"
        const val TABLE_MEDIA = "media"
    }
}
