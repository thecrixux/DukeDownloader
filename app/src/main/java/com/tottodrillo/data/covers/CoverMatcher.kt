package com.tottodrillo.data.covers

import java.text.Normalizer
import java.util.Locale

/** Identity matching only: never guess by substring or cross console boundaries. */
object CoverMatcher {
    private val groups = Regex("\\(([^()]*)\\)|\\[([^\\[\\]]*)]")
    private val regions = mapOf(
        "usa" to "US", "us" to "US", "europe" to "EU", "eur" to "EU", "pal" to "EU",
        "japan" to "JP", "jpn" to "JP", "ntsc-j" to "JP", "ntsc-u" to "US",
        "australia" to "AU", "asia" to "AS", "korea" to "KR", "china" to "CN",
        "world" to "WW", "worldwide" to "WW", "brazil" to "BR", "canada" to "CA"
    )
    private val language = Regex("(?i)(en|fr|de|es|it|ja|ko|zh|nl|pt|sv|no|da|fi|pl|ru|cs|hu|tr|el|ar)([-, ]+(en|fr|de|es|it|ja|ko|zh|nl|pt|sv|no|da|fi|pl|ru|cs|hu|tr|el|ar))*")
    private val technical = Regex("(?i)(rev(?:ision)?[ ._-]*[a-z0-9.]+|v[0-9]+(?:\\.[0-9]+)*|disc[ ._-]*[0-9]+|disk[ ._-]*[0-9]+|track[ ._-]*[0-9]+|iso|chd|wbfs|rvz|nkit|!|b[0-9]*|o[0-9]*)")
    private val extensions = Regex("(?i)\\.(png|zip|7z|rar|iso|chd|wbfs|rvz|cso|bin|cue)$")
    data class Name(val identity: String, val regions: Set<String>)
    data class Candidate(val filename: String, val regions: Set<String>)

    fun isFrontShape(platform: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return if (platform in setOf("ps2", "wii", "wiiu", "gc", "gamecube", "psp")) {
            width.toDouble() / height in 0.45..0.9
        } else width.toDouble() / height in 0.35..1.7
    }

    fun region(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "WORLD", "WW" -> "WW"
        "USA", "NTSC-U" -> "US"
        "EUR", "PAL" -> "EU"
        "JPN", "NTSC-J" -> "JP"
        else -> code.uppercase(Locale.ROOT)
    }

    fun parse(value: String): Name {
        var title = value
        while (extensions.containsMatchIn(title)) title = title.replace(extensions, "")
        val found = linkedSetOf<String>()
        title = groups.replace(title) { match ->
            val tag = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim()
            val countries = tag.lowercase(Locale.ROOT).split(',').map { it.trim() }
            when {
                countries.isNotEmpty() && countries.all { it in regions } -> {
                    countries.forEach { found.add(regions.getValue(it)) }; " "
                }
                language.matches(tag) || technical.matches(tag) -> " "
                else -> " $tag " // Editions, prototypes, betas, hacks and sequels remain distinct.
            }
        }
        title = title.replace(Regex("(?i),\\s*(the|a|an)\\s*$"), "")
            .replace(Regex("(?i)^(the|a|an)\\s+"), "")
        val identity = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return Name(identity, found)
    }

    fun index(filenames: List<String>): Map<String, List<Candidate>> = filenames
        .filter { it.endsWith(".png", true) && !it.contains('/') && !it.contains('\\') }
        .groupBy { parse(it).identity }
        .mapValues { (_, names) -> names.map { Candidate(it, parse(it).regions) } }

    fun choose(title: String, requestedRegions: List<String>, index: Map<String, List<Candidate>>): Candidate? {
        val parsed = parse(title)
        if (parsed.identity.isEmpty()) return null
        val wanted = requestedRegions.map(::region).filter { it != "WW" }.distinct()
            .ifEmpty { parsed.regions.filter { it != "WW" } }
        return index[parsed.identity].orEmpty().mapNotNull { candidate ->
            val rank = when {
                wanted.isEmpty() -> if (candidate.regions == setOf("WW")) 0 else return@mapNotNull null
                candidate.regions.any { it in wanted } -> wanted.indexOfFirst { it in candidate.regions }
                candidate.regions == setOf("WW") -> 100
                else -> return@mapNotNull null // No unlabelled or different-region substitution.
            }
            Triple(rank, if (parse(title).regions == candidate.regions) 0 else 1, candidate)
        }.sortedWith(compareBy<Triple<Int, Int, Candidate>> { it.first }.thenBy { it.second }.thenBy { it.third.filename })
            .firstOrNull()?.third
    }
}
