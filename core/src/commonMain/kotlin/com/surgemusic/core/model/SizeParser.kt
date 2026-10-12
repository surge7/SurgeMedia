package com.surgemusic.core.model

object SizeParser {
    private val pattern = Regex("([\\d.,]+)\\s*([КкMмGгTtKk]?[БбBb]|КБ|МБ|ГБ|ТБ|KB|MB|GB|TB|KiB|MiB|GiB|TiB)")

    fun parseBytes(text: String): Long {
        val cleaned = text.replace("\u00A0", " ").replace(160.toChar(), ' ')
        val match = pattern.find(cleaned) ?: return 0L
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].trim().uppercase()
        val mult = when (unit.firstOrNull()) {
            'К', 'K' -> 1024L
            'М', 'M' -> 1024L * 1024
            'Г', 'G' -> 1024L * 1024 * 1024
            'Т', 'T' -> 1024L * 1024 * 1024 * 1024
            else -> 1L
        }
        return (number * mult).toLong()
    }

    fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble()
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        val s = if (value >= 100 || i == 0) value.toLong().toString() else String.format("%.2f", value)
        return "$s ${units[i]}"
    }
}
