package com.memorymap.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Room stores dates as ISO-8601 text. Text keeps the database readable, keeps
 * `LIKE '____-09-23'` usable for "on this day", and sorts chronologically.
 */
class Converters {

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)

    @TypeConverter
    fun localDateTimeToString(value: LocalDateTime?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDateTime(value: String?): LocalDateTime? =
        value?.takeIf { it.isNotBlank() }?.let(LocalDateTime::parse)
}
