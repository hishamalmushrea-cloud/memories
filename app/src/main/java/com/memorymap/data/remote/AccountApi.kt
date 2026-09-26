package com.memorymap.data.remote

import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two destructive operations a client can only do through the server.
 *
 * A device holding the anon key and the user's own JWT has no rights on
 * `auth.users` and no way to remove its own rows in one statement - Row Level
 * Security would only let it delete row by row, and it could never delete the
 * account. Both live in `supabase/schema.sql` as `security definer` functions
 * that check `auth.uid()`, so they can only ever touch the caller's own account.
 *
 * An interface because the failure that matters here is the one that arrives
 * halfway: a deletion that cannot reach the server must not wipe the device and
 * then claim success, and that is only testable against a double that fails on
 * demand.
 */
interface AccountApi {

    /** Everything the account wrote on the server, keeping the account itself. */
    suspend fun deleteRecords()

    /** The records, the profile row and the auth user: the account is gone. */
    suspend fun deleteAccount()
}

@Singleton
class PostgrestAccountApi @Inject constructor(
    private val provider: SupabaseClientProvider,
) : AccountApi {

    override suspend fun deleteRecords() {
        client().postgrest.rpc(DELETE_RECORDS)
    }

    override suspend fun deleteAccount() {
        client().postgrest.rpc(DELETE_ACCOUNT)
    }

    private fun client() = provider.get()
        ?: throw IllegalStateException("Cloud sync is not configured on this device")

    companion object {
        /**
         * The function names, which have to match `supabase/schema.sql` exactly.
         *
         * A rename on either side is a silent 404 from Postgrest at the one
         * moment a user is asking to be deleted, so `SupabaseContractTest`
         * reads both files and fails the build when they disagree.
         */
        const val DELETE_RECORDS = "delete_my_data"
        const val DELETE_ACCOUNT = "delete_my_account"
    }
}
