package com.tottodrillo.data.covers

import java.text.Normalizer
import java.util.Locale

/** Identity matching only: never guess by substring or cross console boundaries. */
object CoverMatcher {
    private val groups = Regex("\\(([^()]*)\\)|\\[([^\\[\\]]*)]")
    private val regions = mapOf(
        "usa" to "US", "us" to "US", "u" to "US", "e" to "EU", "j" to "JP", "europe" to "EU", "eur" to "EU", "pal" to "EU",
        "eu" to "EU", "jp" to "JP", "japan" to "JP", "jpn" to "JP", "ntsc-j" to "JP", "ntsc-u" to "US",
        "australia" to "AU", "asia" to "AS", "korea" to "KR", "china" to "CN",
        "world" to "WW", "worldwide" to "WW", "brazil" to "BR", "canada" to "CA",
        "spain" to "ES", "france" to "FR", "germany" to "DE", "italy" to "IT",
        "united kingdom" to "UK", "uk" to "UK", "netherlands" to "NL", "portugal" to "PT",
        "sweden" to "SE", "norway" to "NO", "denmark" to "DK", "finland" to "FI",
        "poland" to "PL", "russia" to "RU", "taiwan" to "TW", "hong kong" to "HK"
    )
    private val language = Regex("(?i)(en|fr|de|es|it|ja|ko|zh|nl|pt|sv|no|da|fi|pl|ru|cs|hu|tr|el|ar)([-, ]+(en|fr|de|es|it|ja|ko|zh|nl|pt|sv|no|da|fi|pl|ru|cs|hu|tr|el|ar))*")
    private val technical = Regex("(?i)(rev(?:ision)?[ ._-]*[a-z0-9.]+|v[0-9]+(?:\\.[0-9]+)*|disc[ ._-]*[0-9]+|disk[ ._-]*[0-9]+|track[ ._-]*[0-9]+|iso|chd|wbfs|rvz|nkit|!|b[0-9]*|o[0-9]*)")
    private val extensions = Regex("(?i)\\.(png|zip|7z|rar|iso|chd|wbfs|rvz|cso|bin|cue|nds|3ds|cia|wad|gba|gbc|gb|nes|sfc|smc|n64|z64|v64|md|smd|gen|pbp)$")
    data class Name(val identity: String, val regions: Set<String>)
    data class Candidate(val filename: String, val regions: Set<String>)

    /** Exact aliases only; source category paths are not catalogue identifiers. */
    fun platformCode(value: String): String {
        val name = value.trim().substringAfterLast('/').lowercase(Locale.ROOT)
        val key = name.replace(Regex("[^a-z0-9]"), "")
        return when (key) {
            "playstation2", "sonyplaystation2", "ps2" -> "ps2"
            "playstation", "playstation1", "sonyplaystation", "ps1", "psx" -> "psx"
            "playstationportable", "sonypsp", "psp" -> "psp"
            "nintendowii", "wii" -> "wii"
            "wiiware", "nintendowiiware" -> "wiiware"
            "nintendowiiu", "wiiu" -> "wiiu"
            "nintendogamecube", "gamecube", "gc" -> "gc"
            "nintendoentertainmentsystem", "nes" -> "nes"
            "supernintendo", "supernintendoentertainmentsystem", "snes" -> "snes"
            "nintendo64", "n64" -> "n64"
            "gameboy", "nintendogameboy", "gb" -> "gb"
            "gameboycolor", "nintendogameboycolor", "gbc" -> "gbc"
            "gameboyadvance", "nintendogameboyadvance", "gba" -> "gba"
            "nintendods", "nds" -> "nds"
            "nintendo3ds", "n3ds", "3ds" -> "3ds"
            "segadreamcast", "dreamcast", "dc" -> "dreamcast"
            "segasaturn", "saturn" -> "saturn"
            "segamegadrive", "segagenesis", "megadrive", "genesis", "smd" -> "megadrive"
            "segamastersystem", "mastersystem", "sms" -> "sms"
            "segagamegear", "gamegear", "gg" -> "gg"
            "atarilynx", "lynx" -> "lynx"
            "atarijaguar", "jaguar" -> "jaguar"
            "pcengine", "necpcengine", "turbografx16", "pce" -> "pce"
            else -> name
        }
    }

    fun isFrontShape(platform: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return if (platformCode(platform) in setOf("ps2", "wii", "wiiu", "gc", "gamecube", "psp")) {
            width.toDouble() / height in 0.45..0.9
        } else width.toDouble() / height in 0.35..1.7
    }

    fun region(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "WORLD", "WW" -> "WW"
        "USA", "NTSC-U" -> "US"
        "EUR", "EUROPE", "PAL" -> "EU"
        "JPN", "JAPAN", "NTSC-J" -> "JP"
        "ASIA" -> "AS"
        else -> code.uppercase(Locale.ROOT)
    }

    fun parse(value: String): Name {
        var title = value.trim()
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
                countries.isNotEmpty() && countries.all { it in regions || language.matches(it) || technical.matches(it) } -> {
                    countries.mapNotNull { regions[it] }.forEach { found.add(it) }; " "
                }
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

    // Only explicitly tagged variants may fall back to the commercial title.
    // Named hacks, compilations, sequels and unknown tags retain their identity.
    private val variant = Regex("(?i)(demo(?: [0-9]+)?|beta(?: [0-9.]+)?|proto(?:type)?(?: [0-9.]+)?|sample|preview|pirate|pirated|hack|patched|translation(?: [a-z-]+)?|t[+!-].*)")
    fun commercialTitle(value: String): String {
        var removedVariant = false
        val retail = groups.replace(value) { match ->
            val tag = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim()
            val parts = tag.split(',').map { it.trim() }
            val remaining = parts.filterNot { variant.matches(it) }
            if (remaining.size != parts.size) removedVariant = true
            if (remaining.size == parts.size) match.value
            else if (remaining.isEmpty()) " " else "(" + remaining.joinToString(", ") + ")"
        }

        // Build dates are metadata only when another tag identified a known variant.
        return if (removedVariant) retail.replace(Regex("\\((?:19|20)[0-9]{2}-[0-9xX]{2}-[0-9xX]{2}\\)"), " ") else retail
    }

    /** Exact edition/region first, then the same title in another region, then retail. */
    fun chooseCover(title: String, requestedRegions: List<String>, index: Map<String, List<Candidate>>, platform: String): Candidate? {
        choose(title, requestedRegions, index)?.let { return it }
        fun sameGame(name: String): Candidate? {
            choose(name, requestedRegions, index)?.let { return it }
            val candidates = index[parse(name).identity].orEmpty()
            // No edit-distance/substring guessing: all candidates have the same full identity.
            return candidates.sortedWith(compareBy<Candidate> {
                when {
                    "WW" in it.regions -> 0
                    "US" in it.regions -> 1
                    "EU" in it.regions -> 2
                    "JP" in it.regions -> 3
                    it.regions.isNotEmpty() -> 4
                    else -> 5
                }
            }.thenBy { it.filename }).firstOrNull()
        }
        sameGame(title)?.let { return it }
        val retail = commercialTitle(title)
        sameGame(retail)?.let { return it }
        // A verified title alias, scoped to PS2, not a generic prefix deletion.
        if (platformCode(platform) == "ps2" && parse(retail).identity == "lara croft tomb raider legend") {
            return sameGame("Tomb Raider Legend")
        }
        return null
    }

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
