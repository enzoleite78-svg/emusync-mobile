package app.emusyncmobile.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Datas no formato que o System.Text.Json do .NET usa para DateTime UTC:
 * "2026-11-10T21:03:11.1234567Z" (7 casas decimais).
 */
object DotNetTime {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = formatter.format(instant)

    fun now(): String = format(Instant.now())

    fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            // DateTime sem fuso: o EmuSync sempre grava UTC
            ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** Compara duas datas do Drive ignorando diferenças de formatação. */
    fun same(a: String?, b: String?): Boolean = parse(a) == parse(b)
}
