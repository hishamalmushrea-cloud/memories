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
 * The four link tables.
 *
 * These carry no timestamps and no status of their own: a link is not a row
 * with a history, it is a fact about its record. So they travel with the record
 * that owns them and are replaced wholesale, which is what makes an unlink
 * reach another device instead of being merged back in.
 */
/**
 * An attachment's row, which is metadata only: the bytes live in a bucket at
 * [storagePath], and the two are written in that order so a row never names
 * an object that is not there yet.
 */
@Serializable
data class MediaRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("owner_type") val ownerType: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("mime_type") val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_status") val syncStatus: String = "SYNCED",
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
)

@Serializable
data class MemoryPersonLink(
    @SerialName("memory_id") val memoryId: String,
    @SerialName("person_id") val personId: String,
)

@Serializable
data class MemoryPlaceLink(
    @SerialName("memory_id") val memoryId: String,
    @SerialName("place_id") val placeId: String,
)

@Serializable
data class EntryPersonLink(
    @SerialName("entry_id") val entryId: String,
    @SerialName("person_id") val personId: String,
)

@Serializable
data class EntryPlaceLink(
    @SerialName("entry_id") val entryId: String,
    @SerialName("place_id") val placeId: String,
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

    /**
     * Replaces the links of the given records in one step.
     *
     * The ids are passed alongside the links because a record whose links have
     * all been removed still has to be in the list, or its old links would
     * survive on the server forever.
     */
    suspend fun replaceMemoryPeople(memoryIds: List<String>, links: List<MemoryPersonLink>)

    suspend fun replaceMemoryPlaces(memoryIds: List<String>, links: List<MemoryPlaceLink>)

    suspend fun replaceEntryPeople(entryIds: List<String>, links: List<EntryPersonLink>)

    suspend fun replaceEntryPlaces(entryIds: List<String>, links: List<EntryPlaceLink>)

    suspend fun fetchMemoryPeople(memoryIds: List<String>): List<MemoryPersonLink>

    suspend fun fetchMemoryPlaces(memoryIds: List<String>): List<MemoryPlaceLink>

    suspend fun fetchEntryPeople(entryIds: List<String>): List<EntryPersonLink>

    suspend fun fetchEntryPlaces(entryIds: List<String>): List<EntryPlaceLink>

    /** Sends the metadata of attachments whose bytes are already in the bucket. */
    suspend fun upsertMedia(rows: List<MediaRecord>)

    /**
     * Attachments changed since [since].
     *
     * Filtered on `updated_at`, like every other table: an attachment deleted on
     * another device keeps its original `created_at`, so a window built on that
     * column would step straight over the tombstone and the deletion would never
     * arrive.
     */
    suspend fun fetchMedia(userId: String, since: String?): List<MediaRecord>
}
