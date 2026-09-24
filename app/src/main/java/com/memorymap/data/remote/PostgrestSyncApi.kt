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

    private fun table(name: String): PostgrestQueryBuilder {
        val client = provider.get()
            ?: throw IllegalStateException("Cloud sync is not configured on this device")
        return client.postgrest.from(name)
    }

    private companion object {
        const val TABLE_MEMORIES = "memories"
        const val TABLE_ENTRIES = "daily_entries"
    }
}
