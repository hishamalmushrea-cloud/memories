package com.memorymap.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The account's row in `public.profiles`.
 *
 * This is not a synchronised table: there is one row per account, it is written
 * when a session appears, and nothing else in the app reads it. It exists because
 * every other table's `user_id` is a foreign key to it, so the row has to be
 * there before the first memory can be uploaded - otherwise the server refuses
 * the write with a foreign key violation, on every table, for every user, and the
 * user sees a sync that never succeeds.
 *
 * `supabase/schema.sql` also creates it from a trigger on `auth.users`, which is
 * what covers a user created outside the app. Writing it here as well keeps an
 * older project working, on the one path that runs before any sync.
 */
@Serializable
data class ProfileRecord(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
)
