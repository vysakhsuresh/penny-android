package app.penny.core.io

/** RFC 4180 CSV with auto-detected delimiter (comma, semicolon, tab, pipe). */
object Csv {
    fun write(rows: List<List<String>>, delimiter: Char = ','): String = buildString {
        rows.forEach { r ->
            append(r.joinToString(delimiter.toString()) { cell ->
                // Neutralise spreadsheet formula injection on export.
                val safe = if (cell.isNotEmpty() && cell[0] in "=+-@" && cell.toBigDecimalOrNull() == null) "'$cell" else cell
                if (safe.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
            })
            append("\r\n")
        }
    }

    fun detectDelimiter(text: String): Char {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> line.count { it == d } } ?: ','
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                delimiter -> { row += cell.toString(); cell.clear() }
                '\r' -> Unit
                '\n' -> { row += cell.toString(); cell.clear(); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); if (row.any { it.isNotEmpty() }) rows += row }
        return rows
    }
}
