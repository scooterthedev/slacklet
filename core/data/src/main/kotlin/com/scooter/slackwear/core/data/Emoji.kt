package com.scooter.slackwear.core.data

import android.content.Context
import android.util.Log

object Emoji {

    @Volatile
    private var table: Map<String, String> = emptyMap()

    @Volatile
    private var names: Map<String, String> = emptyMap()

    fun initialize(context: Context) {
        if (table.isNotEmpty()) return
        table = runCatching { readTable(context) }
            .onFailure { Log.w(TAG, "Emoji table unavailable", it) }
            .getOrDefault(emptyMap())
        names = table.entries.associate { (name, glyph) -> glyph to name }
    }

    private fun readTable(context: Context): Map<String, String> =
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator)
                val glyph = line.substring(separator + 1).toGlyph() ?: return@mapNotNull null
                name to glyph
            }.toMap()
        }

    private fun String.toGlyph(): String? = runCatching {
        split('-').joinToString("") { String(Character.toChars(it.toInt(16))) }
    }.getOrNull()

    private val SHORTCODE = Regex(":([a-z0-9_+'\\-]+)(::skin-tone-\\d)?:", RegexOption.IGNORE_CASE)

    fun resolve(text: String): String =
        SHORTCODE.replace(text) { match ->
            table[match.groupValues[1].lowercase()] ?: match.value
        }

    fun searchable(): List<String> = table.keys.sorted()

    fun glyphFor(name: String): String? = table[name.lowercase()] ?: name.takeIf(::isGlyph)

    fun codepointGlyph(value: String): String? = value.takeIf(CODEPOINTS::matches)?.toGlyph()

    fun canonicalName(symbol: String): String? {
        if (table.containsKey(symbol.lowercase())) return symbol.lowercase()
        val glyph = symbol.takeIf(::isGlyph) ?: codepointGlyph(symbol) ?: return null
        return names[glyph]
    }

    private fun isGlyph(value: String): Boolean =
        value.isNotEmpty() && value.codePoints().allMatch { it > 0x2000 }

    private val CODEPOINTS = Regex("[0-9a-fA-F]{4,6}(-[0-9a-fA-F]{4,6})*")

    private const val ASSET = "emoji.tsv"
    private const val TAG = "Emoji"
}
