package com.tirthankar.ascent.engine

/**
 * CSV for the data export (PHASE5.md section 3): UTF-8, RFC 4180 quoting (a field with a
 * comma, quote, CR or LF is quoted, and quotes are doubled), CRLF line ends.
 */
object Csv {
    const val EOL = "\r\n"

    fun field(value: String?): String {
        val s = value ?: return ""
        val quote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun row(values: List<String?>): String = values.joinToString(",", transform = ::field) + EOL

    /** Reads RFC 4180 text back into rows (for tests and checks). */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() {
            row.add(field.toString())
            field.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> quoted = true
                c == ',' -> endField()
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { endField(); rows.add(row); row = mutableListOf(); i++ }
                c == '\n' -> { endField(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            endField()
            rows.add(row)
        }
        return rows
    }
}

/**
 * The export's files and their columns, in order (PHASE5.md section 3). The README in
 * the export documents exactly these; a test checks they agree.
 */
object ExportColumns {
    const val QUESTS = "quests.csv"
    const val DAILY = "daily.csv"
    const val HABITS = "habits.csv"
    const val XP_LOG = "xp_log.csv"
    const val README = "README.txt"

    val quests = listOf(
        "id", "seriesId", "scope", "realm", "title", "tier", "target", "unit", "progress", "status", "source",
        "issuedAt", "completedAt", "paidXp", "guardMultiplier", "measurementMode", "legacyProgress",
    )
    val daily = listOf(
        "date", "level", "totalXp", "hp", "mp", "streak",
        "bodyDone", "bodyTotal", "mindDone", "mindTotal", "selfDone", "selfTotal", "neutral",
    )
    val habits = listOf("seriesId", "title", "periodKey", "x", "strength")
    val xpLog = listOf("timestamp", "source", "amount", "multipliers")

    const val PLANS = "plans.csv"
    const val PLAN_ITEMS = "plan_items.csv"
    const val SESSIONS = "sessions.csv"
    val plans = listOf("id", "targetDay", "state", "writtenAt", "completedAt")
    val planItems = listOf("id", "planId", "position", "title", "questId", "mode", "target", "unit", "progress", "done")
    val sessions = listOf("id", "questId", "itemId", "state", "startedAt", "endsAt", "activeSeconds", "endedAt", "quantity", "origin", "note")
    val files: Map<String, List<String>> = linkedMapOf(QUESTS to quests, DAILY to daily, HABITS to habits, XP_LOG to xpLog, PLANS to plans, PLAN_ITEMS to planItems, SESSIONS to sessions)
}

/** Reads the column list for one file out of the README: indented `name:` lines under its heading. */
object ExportReadme {
    private val columnLine = Regex("""^ {2}([A-Za-z]+):""")

    fun columns(readme: String, file: String): List<String> {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.startsWith(file) }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(" ") }
            .mapNotNull { columnLine.find(it)?.groupValues?.get(1) }
    }
}
