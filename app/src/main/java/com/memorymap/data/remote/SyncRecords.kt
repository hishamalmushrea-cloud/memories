package com.memorymap.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shape of the tables that synchronise.
 *
 * Column names follow `supabase/schema.sql`, not Room: the server calls the body
 * `body` and dates `entry_date` / `entry_time`. Keeping the two apart is the
 * point of these classes — nothing outside the remote layer sees them.
 *
 * Timestamps travel as instants. Room keeps naive local text, and the conversion
 * happens in [com.memorymap.data.sync] where it can be tested.
 */

@Serializable
data class MemoryRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val body: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("place_name") val placeName: String? = null,
    @SerialName("memory_date") val memoryDate: String,
    val emotion: String,
    val visibility: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_status") val syncStatus: String = "SYNCED",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class EntryRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("entry_date") val entryDate: String,
    @SerialName("entry_time") val entryTime: String,
    val title: String,
    val body: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("place_id") val placeId: String? = null,
    val emotion: String? = null,
    @SerialName("linked_memory_id") val linkedMemoryId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_status") val syncStatus: String = "SYNCED",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class PersonRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_status") val syncStatus: String = "SYNCED",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class PlaceRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_status") val syncStatus: String = "SYNCED",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

/**
 * The remote half of synchronisation.
 *
 * An interface so the engine and the tables can be tested against a fake that
 * throws on demand — which is the only honest way to test what happens when a
 * phone loses the network halfway through.
 */
interface SyncApi {

    suspend fun upsertMemories(rows: List<MemoryRecord>)

    suspend fun upsertEntries(rows: List<EntryRecord>)

    /** Rows changed after [since], tombstones included so deletes propagate. */
    suspend fun fetchMemories(userId: String, since: String?): List<MemoryRecord>

    suspend fun fetchEntries(userId: String, since: String?): List<EntryRecord>

    suspend fun upsertPeople(rows: List<PersonRecord>)

    suspend fun upsertPlaces(rows: List<PlaceRecord>)

    suspend fun fetchPeople(userId: String, since: String?): List<PersonRecord>

    suspend fun fetchPlaces(userId: String, since: String?): List<PlaceRecord>
}
