package com.tirthankar.ascent.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PHASE5.md section 6: CSV quoting, and the README's columns match the files'. */
class ExportRulesTest {

    @Test
    fun `fields with commas, quotes and line breaks are quoted RFC 4180 style`() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a, b\"", Csv.field("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
        assertEquals("\"cr\rhere\"", Csv.field("cr\rhere"))
        assertEquals("", Csv.field(null))
        assertEquals("a,\"b,c\",\r\n", Csv.row(listOf("a", "b,c", null)))
    }

    @Test
    fun `what is written reads back unchanged`() {
        val rows = listOf(
            listOf("id", "title"),
            listOf("q1", "He said \"go\", then\r\nleft"),
            listOf("q2", ""),
            listOf("q3", "Ünïcødé · ✓"),
        )
        val text = rows.joinToString("") { Csv.row(it) }
        assertEquals(rows, Csv.parse(text))
    }

    @Test
    fun `the shipped README documents every column, in order`() {
        val readme = File("../data/src/main/assets/export/README.txt").readText()
        ExportColumns.files.forEach { (file, columns) ->
            assertEquals(columns, ExportReadme.columns(readme, file), file)
        }
        assertTrue("0.2^(1/30)" in readme && "max(0.5^(n - 3), 0.1)" in readme, "the formulas are written out")
    }
}
