package com.phonecast.android

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Last few requests the TV app made to the phone, shown on the main screen for troubleshooting. */
object RequestLog {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$time $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(none yet)" else lines.joinToString("\n")

    private const val MAX_LINES = 10
}
