package kc.ac.uc.speechbridge

import kotlin.time.Instant
import platform.Foundation.*

actual fun displayDate(iso: String): String = runCatching {
    val date = NSDate.dateWithTimeIntervalSince1970(Instant.parse(iso).toEpochMilliseconds() / 1000.0)
    NSDateFormatter().apply {
        locale = NSLocale("ko_KR")
        timeZone = NSTimeZone.localTimeZone
        dateFormat = "yyyy.MM.dd"
    }.stringFromDate(date)
}.getOrDefault(iso.take(10))
