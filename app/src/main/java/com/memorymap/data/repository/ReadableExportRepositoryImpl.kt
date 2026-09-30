package com.memorymap.data.repository

import android.content.Context
import android.net.Uri
import com.memorymap.R
import com.memorymap.data.local.Mappers
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.repository.ReadableExportOutcome
import com.memorymap.domain.repository.ReadableExportRepository
import com.memorymap.domain.usecase.MarkdownExport
import com.memorymap.util.MmLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a readable copy of the archive from the local database and writes it to
 * a file the user picked.
 *
 * The records are read here, their labels resolved in the user's own language, and
 * the rendering and escaping left to [MarkdownExport] - so the half that decides
 * what the document says is pure and tested, and this class only gathers inputs and
 * moves bytes. Everything happens on the device; the write goes to the Storage
 * Access Framework document the user chose, and nothing is uploaded.
 */
@Singleton
class ReadableExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryDao: MemoryDao,
    private val dailyEntryDao: DailyEntryDao,
    private val placeDao: PlaceDao,
) : ReadableExportRepository {

    override suspend fun render(userId: String): String = withContext(Dispatchers.IO) {
        val locale = currentLocale()
        // One read of the user's places, so an event's place is a lookup in memory
        // rather than a query per event.
        val placeNames = placeDao.allForUser(userId).associate { it.id to it.name }
        val memories = memoryDao.allForUser(userId).map { entity ->
            val memory = with(Mappers) { entity.toDomain() }
            MarkdownExport.Record(
                date = memory.memoryDate,
                dateLabel = memory.memoryDate.formatLong(locale),
                kindLabel = context.getString(R.string.export_kind_memory),
                title = memory.title,
                body = memory.text,
                emotionLabel = context.getString(emotionLabelRes(memory.emotion)),
                placeName = memory.placeName,
            )
        }
        val entries = dailyEntryDao.allForUser(userId).map { entity ->
            val entry = with(Mappers) { entity.toDomain() }
            MarkdownExport.Record(
                date = entry.date,
                dateLabel = entry.date.formatLong(locale),
                kindLabel = context.getString(R.string.export_kind_entry),
                title = entry.title,
                body = entry.text,
                emotionLabel = entry.emotion?.let { context.getString(emotionLabelRes(it)) },
                placeName = entry.placeId?.let { placeNames[it] },
            )
        }
        MarkdownExport.render(
            context.getString(R.string.export_document_title),
            memories + entries,
        )
    }

    override suspend fun export(userId: String, documentUri: String): ReadableExportOutcome {
        val markdown = render(userId)
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(documentUri)
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("No output stream for $documentUri")
                out.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
            }.fold(
                onSuccess = { ReadableExportOutcome.Written },
                onFailure = { error ->
                    MmLog.e("Readable export failed", error)
                    ReadableExportOutcome.Failed
                },
            )
        }
    }

    private fun currentLocale(): Locale =
        context.resources.configuration.locales?.takeUnless { it.isEmpty }?.get(0)
            ?: Locale.getDefault()

    private fun LocalDate.formatLong(locale: Locale): String =
        format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    /** The emotion's name in the user's language, mirroring the label the app shows. */
    private fun emotionLabelRes(emotion: Emotion): Int = when (emotion) {
        Emotion.HAPPY -> R.string.emotion_happy
        Emotion.SAD -> R.string.emotion_sad
        Emotion.LOVE -> R.string.emotion_love
        Emotion.FEAR -> R.string.emotion_fear
        Emotion.PRIDE -> R.string.emotion_pride
        Emotion.NOSTALGIA -> R.string.emotion_nostalgia
    }
}
