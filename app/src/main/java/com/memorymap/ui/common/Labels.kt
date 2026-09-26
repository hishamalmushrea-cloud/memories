package com.memorymap.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.memorymap.R
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Visibility
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localised name of an emotion. Shared by the profile, editor and detail. */
@Composable
fun emotionLabel(emotion: Emotion): String = when (emotion) {
    Emotion.HAPPY -> stringResource(R.string.emotion_happy)
    Emotion.SAD -> stringResource(R.string.emotion_sad)
    Emotion.LOVE -> stringResource(R.string.emotion_love)
    Emotion.FEAR -> stringResource(R.string.emotion_fear)
    Emotion.PRIDE -> stringResource(R.string.emotion_pride)
    Emotion.NOSTALGIA -> stringResource(R.string.emotion_nostalgia)
}

/** Localised name of a visibility level. */
@Composable
fun visibilityLabel(visibility: Visibility): String = when (visibility) {
    Visibility.PRIVATE -> stringResource(R.string.visibility_private)
    Visibility.SHARED -> stringResource(R.string.visibility_shared)
    Visibility.PUBLIC -> stringResource(R.string.visibility_public)
}

/** Localised name of an attachment kind. */
@Composable
fun mediaTypeLabel(type: MediaType): String = when (type) {
    MediaType.PHOTO -> stringResource(R.string.media_type_photo)
    MediaType.AUDIO -> stringResource(R.string.media_type_audio)
    MediaType.VIDEO -> stringResource(R.string.media_type_video)
}

/** A long, locale-aware date such as `23 September 2026`. */
fun LocalDate.formatLong(locale: Locale): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

/**
 * A short, locale-aware date and time, for "last synchronised at".
 *
 * Returns null rather than throwing when the stored text cannot be read: a
 * timestamp the app cannot parse is worth skipping, not worth a crash over.
 */
fun formatDateTime(text: String?, locale: Locale): String? {
    if (text.isNullOrBlank()) return null
    val parsed = runCatching { LocalDateTime.parse(text) }.getOrNull() ?: return null
    return parsed.format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale),
    )
}
