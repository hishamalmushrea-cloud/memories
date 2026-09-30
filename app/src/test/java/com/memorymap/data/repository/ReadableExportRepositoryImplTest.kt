package com.memorymap.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.R
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.SyncStatus
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The readable export against a real database and real resources.
 *
 * [MarkdownExport] already proves the shape of the document and the escaping in a
 * plain JVM test. What is left to prove here is the assembly around it: that the
 * records are read for the right user only, that a diary event's place and emotion
 * are resolved through the reference table and the label table, and that the
 * user's own words survive the trip out still escaped. Only the write to a SAF
 * document is out of reach here, so the seam under test is [render], the half that
 * decides what the document says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadableExportRepositoryImplTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var context: Context
    private lateinit var repository: ReadableExportRepositoryImpl

    private val userId = "user-1"
    private val otherUser = "user-2"
    private val stamp = "2024-01-01T00:00:00"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryMapDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReadableExportRepositoryImpl(
            context = context,
            memoryDao = db.memoryDao(),
            dailyEntryDao = db.dailyEntryDao(),
            placeDao = db.placeDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `render gathers both kinds of record for the user and no one else`() = runTest {
        seed()

        val markdown = repository.render(userId)

        assertTrue(markdown, markdown.contains("رحلة إلى إب"))
        assertTrue(markdown, markdown.contains("زيارة أحمد"))
        // Another user's memory must not leak into this document.
        assertFalse(markdown, markdown.contains("SECRET-OTHER"))
    }

    @Test
    fun `render labels each record with its kind in the user's language`() = runTest {
        seed()

        val markdown = repository.render(userId)

        assertTrue(markdown, markdown.contains(context.getString(R.string.export_kind_memory)))
        assertTrue(markdown, markdown.contains(context.getString(R.string.export_kind_entry)))
    }

    @Test
    fun `render resolves an event's place through the reference table`() = runTest {
        seed()

        val markdown = repository.render(userId)

        // The event stores only a place id; the name has to be looked up.
        assertTrue(markdown, markdown.contains("حديقة الثورة"))
    }

    @Test
    fun `render resolves an event's emotion to its label`() = runTest {
        seed()

        val markdown = repository.render(userId)

        assertTrue(markdown, markdown.contains(context.getString(R.string.emotion_happy)))
    }

    @Test
    fun `render keeps the user's words escaped so markdown cannot reshape them`() = runTest {
        seed()

        val markdown = repository.render(userId)

        // A title that starts with '#' and a body that starts with '-' would
        // become a heading and a bullet if they were emitted raw. Both come out
        // backslash-escaped, so they read as the text the user typed.
        assertTrue(markdown, markdown.contains("\\# not a heading"))
        assertTrue(markdown, markdown.contains("\\- not a bullet"))
    }

    @Test
    fun `render opens with the document title as its heading`() = runTest {
        seed()

        val markdown = repository.render(userId)

        val title = context.getString(R.string.export_document_title)
        assertTrue(markdown, markdown.startsWith("# $title"))
    }

    private suspend fun seed() {
        db.placeDao().upsert(
            PlaceEntity(
                id = "pl1",
                userId = userId,
                name = "حديقة الثورة",
                latitude = 15.35,
                longitude = 44.20,
                createdAt = stamp,
            ),
        )
        db.memoryDao().upsert(memory("m1", userId, "رحلة إلى إب", text = "يوم جميل"))
        db.memoryDao().upsert(memory("m2", userId, "# not a heading", text = "- not a bullet"))
        db.dailyEntryDao().upsert(
            entry("e1", userId, "زيارة أحمد", placeId = "pl1", emotion = "HAPPY"),
        )
        db.memoryDao().upsert(memory("secret", otherUser, "SECRET-OTHER"))
    }

    private fun memory(
        id: String,
        owner: String,
        title: String,
        text: String = "",
    ) = MemoryEntity(
        id = id,
        userId = owner,
        title = title,
        text = text,
        memoryDate = "2024-01-01",
        emotion = "NOSTALGIA",
        visibility = "PRIVATE",
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.SYNCED.name,
    )

    private fun entry(
        id: String,
        owner: String,
        title: String,
        placeId: String? = null,
        emotion: String? = null,
    ) = DailyEntryEntity(
        id = id,
        userId = owner,
        date = "2024-01-01",
        time = LocalDateTime.of(2024, 1, 1, 10, 0).toString(),
        title = title,
        text = "",
        placeId = placeId,
        emotion = emotion,
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.SYNCED.name,
    )
}
