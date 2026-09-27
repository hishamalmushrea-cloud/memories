package com.memorymap.domain.model

/**
 * Media attached to a memory or a diary entry.
 *
 * Photos, audio and video are independent attachments. The app never transcribes
 * audio and never analyses or summarises video: a video is only a file the user
 * can store, play back and delete.
 */
enum class MediaType {
    PHOTO,
    AUDIO,
    VIDEO;

    companion object {
        fun fromName(value: String?): MediaType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
