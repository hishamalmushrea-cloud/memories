package com.memorymap.data.remote

import com.memorymap.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data contract between the Kotlin records and `supabase/schema.sql`.
 *
 * The client has never been pointed at a real Supabase project, so there is no
 * server anywhere that would reject a column the client does not send or a value
 * the enums do not contain: the first time it would happen is the first time a
 * user with a real archive presses sync, and Postgrest answers that with a 400
 * while their own rows sit unuploaded.
 *
 * Everything below is checked against the SQL that is meant to be applied, not
 * against a copy of it. That is the strongest statement available without a
 * server, and it is deliberately not called a substitute for one: it cannot
 * prove that a policy admits the right user, that a trigger fires, or that the
 * network path works at all. It proves the two halves agree on names, on which
 * columns are mandatory, and on what the enumerations accept.
 */
class SupabaseContractTest {

    private val sql = RepoFiles.read("supabase/schema.sql")
    private val recordsSource =
        RepoFiles.read("app/src/main/java/com/memorymap/data/remote/SyncRecords.kt")
    private val api = RepoFiles.read("app/src/main/java/com/memorymap/data/remote/PostgrestSyncApi.kt")

    private data class Column(val name: String, val notNull: Boolean, val hasDefault: Boolean)

    private data class Record(val name: String, val wireNames: List<String>)

    /** Which table each record is the wire shape of. Adding a record means adding it here. */
    private val tableOf = mapOf(
        "MemoryRecord" to "memories",
        "EntryRecord" to "daily_entries",
        "PersonRecord" to "people",
        "PlaceRecord" to "places",
        "MediaRecord" to "media",
        "DiaryNoteRecord" to "diary_notes",
        "MemoryPersonLink" to "memory_person",
        "MemoryPlaceLink" to "memory_place",
        "EntryPersonLink" to "daily_entry_person",
        "EntryPlaceLink" to "daily_entry_place",
    )

    /** The tables the client names in its own constants. */
    private val clientTables: Set<String> =
        Regex("const val TABLE_\\w+ = \"(\\w+)\"").findAll(api)
            .map { it.groupValues[1] }
            .toSet()

    private val tables: Map<String, List<Column>> by lazy { parseTables() }

    private val records: List<Record> by lazy { parseRecords() }

    @Test
    fun `every table the client talks to exists in the schema`() {
        val missing = clientTables - tables.keys
        assertEquals("tables the client writes but the schema does not define", emptySet<String>(), missing)
    }

    @Test
    fun `the client uses exactly the tables this contract covers`() {
        assertEquals(
            "a table was added without a record here, so nothing checks it",
            tableOf.values.toSet(),
            clientTables,
        )
    }

    @Test
    fun `the contract covers every record in the wire file`() {
        assertEquals(
            "a record was added without a table here, so nothing checks it",
            tableOf.keys,
            records.map { it.name }.toSet(),
        )
    }

    @Test
    fun `every column a record sends is a column of its table`() {
        val problems = mutableListOf<String>()
        records.forEach { record ->
            val table = tableOf.getValue(record.name)
            val columns = tables[table].orEmpty().map { it.name }.toSet()
            record.wireNames.forEach { wire ->
                if (wire !in columns) problems += "${record.name}.$wire is not a column of $table"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every mandatory column is sent by its record`() {
        // A `not null` column with no default is one the client has to supply: a
        // record that omits it fails the insert, and the whole row stays local.
        val problems = mutableListOf<String>()
        tableOf.forEach { (recordName, table) ->
            val sent = records.single { it.name == recordName }.wireNames.toSet()
            tables[table].orEmpty()
                .filter { it.notNull && !it.hasDefault }
                .forEach { column ->
                    if (column.name !in sent) {
                        problems += "$table.${column.name} is not null with no default, " +
                            "and $recordName does not send it"
                    }
                }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the columns a download filters on exist in every table it reads`() {
        // The client scopes every read with `user_id` and asks for rows changed
        // after a watermark. A missing `updated_at` is not a slow sync, it is an
        // error the user sees as a failure to sync at all.
        val downloaded = setOf("memories", "daily_entries", "people", "places", "media", "diary_notes")
        val problems = mutableListOf<String>()
        downloaded.forEach { table ->
            listOf("user_id", "updated_at").forEach { column ->
                if (tables[table].orEmpty().none { it.name == column }) {
                    problems += "$table.$column is filtered on but does not exist"
                }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every link table is keyed the way the client deletes from it`() {
        // Replacing links means deleting the old ones by their owner column and
        // inserting the new set, so that column has to be the one on that exact
        // table: the wrong name deletes somebody else's links, or none at all.
        val calls = Regex("(?:replace|fetchLinks)\\(\\s*TABLE_(\\w+),\\s*\"(\\w+)\"")
            .findAll(api)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("expected the client to name its owner columns", calls.isNotEmpty())

        val tableNames = Regex("const val TABLE_(\\w+) = \"(\\w+)\"").findAll(api)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val ownerOf = mapOf(
            "MEMORY_PERSON" to "memory_id",
            "MEMORY_PLACE" to "memory_id",
            "ENTRY_PERSON" to "entry_id",
            "ENTRY_PLACE" to "entry_id",
        )

        val problems = mutableListOf<String>()
        ownerOf.forEach { (constant, owner) ->
            val table = tableNames[constant]
            if (table == null) {
                problems += "TABLE_$constant is used to replace links but is not declared"
                return@forEach
            }
            if (calls.none { it.first == constant && it.second == owner }) {
                problems += "$constant is replaced by the wrong owner column; expected $owner"
            }
            if (tables[table].orEmpty().none { it.name == owner }) {
                problems += "$table.$owner is used to replace links but does not exist"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the account row exists before anything that points at it`() {
        // Every table the client writes has `user_id references public.profiles`,
        // so that one row has to exist first. Nothing here can be caught by a
        // local test: it is a server constraint, and on a real project the
        // symptom is every table failing to upload with a foreign key violation
        // for every user.
        val references = Regex("references public\\.profiles \\(id\\)").findAll(sql).count()
        assertTrue("expected the schema to reference profiles", references > 0)

        // Path one: the row is created with the auth user.
        assertTrue("no trigger creates the profile row", sql.contains("after insert on auth.users"))
        assertTrue(sql.contains("execute function public.handle_new_user()"))
        assertTrue(sql.contains("insert into public.profiles (id, email, display_name)"))
        assertTrue(
            "the trigger must take the id from the new auth user",
            Regex("values \\(\\s*new\\.id").containsMatchIn(sql),
        )

        // Path two: the client writes it when a session appears, so a project
        // whose schema predates the trigger still syncs, and so the display name
        // the user typed reaches the server at all.
        val auth = RepoFiles.read("app/src/main/java/com/memorymap/data/repository/SupabaseAuthRepository.kt")
        assertTrue("the app never writes the profile row", auth.contains(".from(PROFILE_TABLE).upsert("))
        assertTrue(auth.contains("const val PROFILE_TABLE = \"profiles\""))

        // And the record it sends has to fit the table.
        val profile = RepoFiles.read("app/src/main/java/com/memorymap/data/remote/AuthRecords.kt")
        val sent = Regex(
            "@SerialName\\(\"([^\"]+)\"\\)|^\\s*val (\\w+):",
            RegexOption.MULTILINE,
        ).findAll(profile)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toSet()
        val columns = tables["profiles"].orEmpty().map { it.name }.toSet()
        assertEquals(
            "the profile record and the profile table disagree",
            emptySet<String>(),
            sent - columns,
        )
        listOf("id", "email", "display_name").forEach { required ->
            assertTrue("ProfileRecord must send $required", required in sent)
        }
    }

    @Test
    fun `every synchronised table stamps its rows when they arrive`() {
        // A download asks for rows changed after a watermark, which only works
        // if stamps grow in the order rows are written. The client sends its own
        // edit time, so a phone that edited a row offline and uploaded it after
        // another device synced would store a stamp behind that device's
        // watermark - and that row would never be downloaded anywhere again. No
        // error, no retry, just a memory that exists on one phone.
        assertTrue(
            "the schema does not stamp updated_at on arrival",
            sql.contains("create or replace function public.stamp_updated_at()"),
        )
        assertTrue(
            "the stamp must take the later of the client value and the server clock",
            sql.contains("new.updated_at = greatest(new.updated_at, now())"),
        )

        // Every table the client syncs, and only those: a link table has no
        // updated_at to stamp.
        val synced = clientTables - setOf(
            "memory_person", "memory_place", "daily_entry_person", "daily_entry_place",
        )
        val problems = mutableListOf<String>()
        synced.forEach { table ->
            val trigger = "before insert or update on public.$table"
            if (!sql.contains(trigger)) problems += "$table has no trigger, so its stamps can fall behind"
        }
        assertEquals(emptyList<String>(), problems)

        val triggered = Regex("before insert or update on public\\.(\\w+)").findAll(sql)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            "a trigger was added for a table the client never writes",
            emptySet<String>(),
            triggered - synced,
        )
    }

    @Test
    fun `a download window is inclusive, so the boundary row is re-read`() {
        // The watermark is the newest stamp the device has seen. With a strict
        // bound, a row committed in the same instant as the query that preceded
        // it sits behind that bound forever. Inclusive is safe because the
        // conflict resolver keeps the local copy when the stamps are equal.
        val windows = Regex("""\b(gte?)\("updated_at", since\)""").findAll(api).toList()
        assertEquals("expected six download windows, one per synced table", 6, windows.size)
        assertTrue(
            "every download must use gte, not gt: " + windows.map { it.value }.toString(),
            windows.all { it.groupValues[1] == "gte" },
        )
    }

    @Test
    fun `the enums the client sends are the enums the server accepts`() {
        val pairs = listOf(
            "sync_state" to enumEntries("app/src/main/java/com/memorymap/domain/model/SyncStatus.kt"),
            "emotion_level" to enumEntries("app/src/main/java/com/memorymap/domain/model/Emotion.kt"),
            "media_kind" to enumEntries("app/src/main/java/com/memorymap/domain/model/MediaType.kt"),
            "visibility_level" to enumEntries("app/src/main/java/com/memorymap/domain/model/Visibility.kt"),
        )

        pairs.forEach { (type, clientValues) ->
            assertTrue("expected $type to have values in the schema", enumValues(type).isNotEmpty())
            assertEquals(
                "$type: the server accepts these and the client sends those",
                enumValues(type).toSet(),
                clientValues.toSet(),
            )
        }
    }

    @Test
    fun `the attachment owner values match the check constraint`() {
        val constraint = Regex("owner_type in \\(([^)]*)\\)").find(sql)?.groupValues?.get(1)
        assertNotNull("media.owner_type should be constrained", constraint)

        val accepted = Regex("""'(\w+)'""").findAll(constraint!!).map { it.groupValues[1] }.toSet()
        val sent = enumEntries("app/src/main/java/com/memorymap/domain/model/Models.kt", "MediaOwner")

        assertEquals("media.owner_type accepts these and MediaOwner has those", accepted, sent.toSet())
    }

    private fun parseTables(): Map<String, List<Column>> {
        val result = mutableMapOf<String, List<Column>>()
        var table: String? = null
        val columns = mutableListOf<Column>()

        sql.lines().forEach { raw ->
            val line = raw.trim()
            val start = Regex("""^create table public\.(\w+) \(""").find(line)
            when {
                start != null -> {
                    table = start.groupValues[1]
                    columns.clear()
                }

                table != null && line.startsWith(");") -> {
                    result[table!!] = columns.toList()
                    table = null
                }

                table != null && line.isNotEmpty() && !line.startsWith("--") -> {
                    // Table constraints name columns instead of declaring one.
                    val isConstraint = listOf("primary key", "unique", "constraint", "check (")
                        .any { line.startsWith(it) }
                    if (!isConstraint) {
                        val name = line.substringBefore(' ').substringBefore(',')
                        if (name.isNotEmpty()) {
                            columns += Column(
                                name = name,
                                notNull = line.contains("not null"),
                                hasDefault = line.contains(" default "),
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseRecords(): List<Record> {
        val result = mutableListOf<Record>()
        var name: String? = null
        val fields = mutableListOf<String>()

        recordsSource.lines().forEach { raw ->
            val line = raw.trim()
            val start = Regex("""^data class (\w+)\($""").find(line)
            val isField = line.startsWith("val ") || line.startsWith("@SerialName") ||
                line.startsWith("@SerialName(\"") || line.contains("@SerialName")

            when {
                start != null -> {
                    name = start.groupValues[1]
                    fields.clear()
                }

                name != null && line == ")" -> {
                    result += Record(name!!, fields.toList())
                    name = null
                }

                name != null && isField -> {
                    val property = Regex("""\bval (\w+):""").find(line) ?: return@forEach
                    val wire = Regex("""@SerialName\("([^"]+)"\)""").find(line)
                    fields += wire?.groupValues?.get(1) ?: property.groupValues[1]
                }
            }
        }
        return result
    }

    private fun enumValues(type: String): List<String> {
        val start = sql.indexOf("create type $type as enum (")
        if (start < 0) return emptyList()
        val end = sql.indexOf(");", start)
        return Regex("""'([A-Z_]+)'""").findAll(sql.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    private fun enumEntries(path: String, name: String? = null): List<String> {
        val source = RepoFiles.read(path)
        val declaration = if (name == null) {
            Regex("""enum class (\w+)""").find(source) ?: return emptyList()
        } else {
            Regex("""enum class $name\b""").find(source) ?: return emptyList()
        }
        val start = source.indexOf('{', declaration.range.last)
        if (start < 0) return emptyList()

        // Walk to the closing brace so a single-line enum and a documented
        // multi-line one are read the same way.
        var depth = 0
        var end = start
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }

        val body = source.substring(start + 1, end)
            .lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")

        // Closed again with a brace: a single-line enum's last constant is
        // followed by the end of the body rather than by a comma or a semicolon,
        // and without this the final constant would be read as absent.

        return Regex("""\b([A-Z][A-Z0-9_]+)\s*[(,;}]""")
            .findAll("{$body}")
            .map { it.groupValues[1] }
            .toList()
    }
}
