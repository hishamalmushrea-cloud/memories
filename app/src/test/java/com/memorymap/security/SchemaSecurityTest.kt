package com.memorymap.security

import com.memorymap.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Row Level Security contract, checked against the SQL that will actually
 * be applied to the database.
 *
 * These rules are the only thing standing between one user's diary and
 * another user's query, and nothing in the app can enforce them: a client that
 * asked for somebody else's rows has to be refused by the server. Reading the
 * schema in a test means a policy dropped by a later edit fails the build
 * instead of quietly opening the archive.
 */
class SchemaSecurityTest {

    private val sql = RepoFiles.read("supabase/schema.sql")

    private data class Policy(val name: String, val table: String, val body: String)

    private fun policies(): List<Policy> = sql.split(Regex("(?=create policy )")).mapNotNull { chunk ->
        val match = Regex("""create policy "([^"]+)" on (?:public|storage)\.(\w+)""").find(chunk)
            ?: return@mapNotNull null
        Policy(match.groupValues[1], match.groupValues[2], chunk)
    }

    private fun policiesFor(table: String) = policies().filter { it.table == table }

    private val rlsTables: Set<String> =
        Regex("""alter table public\.(\w+)\s+enable row level security""")
            .findAll(sql).map { it.groupValues[1] }.toSet()

    @Test
    fun `every table the app writes is guarded`() {
        assertEquals(
            setOf(
                "profiles", "places", "people", "memories", "daily_entries", "diary_notes",
                "media", "memory_person", "memory_place", "daily_entry_person",
                "daily_entry_place", "memory_shares",
            ),
            rlsTables,
        )
    }

    @Test
    fun `a guarded table is never left without a policy`() {
        // RLS with no policy denies everything, including the owner, which shows
        // up as a sync that silently writes nothing.
        val withPolicies = policies().map { it.table }.toSet()
        assertEquals(emptySet<String>(), rlsTables - withPolicies)
    }

    @Test
    fun `the diary has no public or shared read path at all`() {
        // §46: the diary is private and sharing is never the default. Here it is
        // stronger than a default - the concept does not exist in the schema.
        val diary = policiesFor("daily_entries") + policiesFor("diary_notes")
        assertTrue("expected diary policies to exist", diary.isNotEmpty())
        diary.forEach { policy ->
            assertFalse(
                "${policy.name} would expose the diary",
                policy.body.contains("PUBLIC") || policy.body.contains("is_shared_with_me"),
            )
        }
    }

    @Test
    fun `a public memory is readable only when explicitly public and not deleted`() {
        val policy = policiesFor("memories").single { it.name == "memories: public read" }

        assertTrue(policy.body.contains("visibility = 'PUBLIC'"))
        assertTrue(policy.body.contains("deleted_at is null"))
        assertTrue(policy.body.contains("auth.role() = 'authenticated'"))
    }

    @Test
    fun `a shared memory is readable only through an explicit grant`() {
        val policy = policiesFor("memories").single { it.name == "memories: shared read" }

        assertTrue(policy.body.contains("visibility = 'SHARED'"))
        assertTrue(policy.body.contains("is_shared_with_me"))
    }

    @Test
    fun `every write policy is limited to the owner`() {
        val writes = policies().filter { policy ->
            Regex("""\bfor (all|insert|update|delete)\b""").containsMatchIn(policy.body)
        }
        assertTrue("expected write policies to exist", writes.isNotEmpty())
        writes.forEach { policy ->
            assertTrue(
                "${policy.name} lets a non-owner write",
                policy.body.contains("is_owner(") || policy.body.contains("auth.uid()"),
            )
        }
    }

    @Test
    fun `a share can be granted only by the owner of the memory`() {
        val policy = policiesFor("memory_shares").single { it.name == "memory_shares: owner manages" }

        assertTrue(policy.body.contains("is_owner(m.user_id)"))
    }

    @Test
    fun `the media bucket is private`() {
        assertTrue(sql.contains("values ('media', 'media', false)"))
    }

    @Test
    fun `media files are reachable only inside the caller's own folder`() {
        val mediaPolicies = policies().filter {
            it.table == "objects" && it.body.contains("bucket_id = 'media'")
        }
        assertTrue("expected media storage policies", mediaPolicies.isNotEmpty())
        mediaPolicies.forEach { policy ->
            assertTrue(
                "${policy.name} is not scoped to the owner's folder",
                policy.body.contains("(storage.foldername(name))[1] = auth.uid()::text"),
            )
        }
    }

    @Test
    fun `the owner can delete their own avatar`() {
        val policy = policies().single { it.name == "avatars: owner deletes own file" }

        assertTrue(policy.body.contains("for delete"))
        assertTrue(policy.body.contains("auth.uid()::text"))
    }

    @Test
    fun `the service role key is never used in the schema`() {
        // It would be a hint that some client somewhere is meant to bypass RLS.
        // The header comment states the rule, so comments are dropped first:
        // what matters is that no statement reaches for the key.
        val statements = sql.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

        assertFalse(statements.contains("service_role"))
    }
}
