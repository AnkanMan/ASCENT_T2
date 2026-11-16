package com.tirthankar.ascent.data

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** Now and the current timezone, injectable so tests can move time. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
}

class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()

    /** Read on every call, so a timezone change takes effect at once. */
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
