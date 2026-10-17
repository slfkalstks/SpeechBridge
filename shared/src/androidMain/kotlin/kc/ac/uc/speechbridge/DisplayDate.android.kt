package kc.ac.uc.speechbridge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual fun displayDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy.MM.dd").format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso.take(10))
