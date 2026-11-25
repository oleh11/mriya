package dev.mriya.domain

import java.util.Locale

enum class StampShape { CIRCLE, DOUBLE_RING, OVAL, ROUNDED_RECT, OCTAGON, SHIELD_ARCH }

enum class StampMood(val label: String) {
    ARRIVAL("ARRIVAL"),
    DEPARTURE("DEPARTURE"),
    TRANSIT("TRANSIT"),
    FIRST_VISIT("FIRST VISIT"),
}

enum class StampInk(val argb: Long) {
    RED(0xFFC8102EL),
    BLACK(0xFF1B1B1BL),
    INDIGO(0xFF1C1B4BL),
    TEAL(0xFF0F766EL),
    PLUM(0xFF6B4EE6L),
    GOLD(0xFFB8840AL),
}

enum class StampEmblem {
    TRIDENT,
    TORII,
    EIFFEL,
    BIG_BEN,
    LIBERTY_STAR,
    COLOSSEUM,
    BRANDENBURG,
    CROWN_SHIELD,
    CRESCENT_BRIDGE,
    MAPLE_LEAF,
    TAIPEI_SPIRE,
    PAGODA,
    TAEGEUK_GATE,
    PYRAMID,
    OPERA_SAILS,
    SWISS_CROSS,
    WINDMILL,
    PARTHENON,
    ALPINE_PEAKS,
    BURJ_SPIRE,
    SUN_ARCH,
    SHAMROCK,
    VOLCANO_WAVE,
    THREE_CROWNS,
    TAJ_DOME,
    PALM_ISLAND,
    CASTLE_SPIRES,
    SOUTHERN_CROSS,
    CONTINENT_FALLBACK,
}

data class StampStyle(
    val shape: StampShape,
    val mood: StampMood,
    val ink: StampInk,
    val continent: String?,
    val rotationDeg: Float,
    val wearSeed: Int,
    val emblem: StampEmblem = StampEmblem.CONTINENT_FALLBACK,
    val entryLabel: String = mood.label,
    val isoCode: String? = null,
    val isSchengen: Boolean = false,
)

private data class CountryStampProfile(
    val shape: StampShape,
    val ink: StampInk,
    val emblem: StampEmblem,
    val entryLabel: String,
    val isSchengen: Boolean = false,
)

object StampDesign {
    private val flatInks = listOf(StampInk.RED, StampInk.BLACK, StampInk.INDIGO, StampInk.TEAL, StampInk.PLUM)

    val continents = listOf(
        "Africa", "Antarctica", "Asia", "Europe", "North America", "Oceania", "South America",
    )

    private val countryProfiles: Map<String, CountryStampProfile> = mapOf(
        // Ukraine & Europe
        "UA" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.INDIGO, StampEmblem.TRIDENT, "В'ЇЗД • ENTRY"),
        "GB" to CountryStampProfile(StampShape.CIRCLE, StampInk.BLACK, StampEmblem.BIG_BEN, "LEAVE TO ENTER"),
        "FR" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.INDIGO, StampEmblem.EIFFEL, "ENTRÉE", isSchengen = true),
        "DE" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.INDIGO, StampEmblem.BRANDENBURG, "EINREISE", isSchengen = true),
        "PL" to CountryStampProfile(StampShape.OCTAGON, StampInk.RED, StampEmblem.CROWN_SHIELD, "WJAZD", isSchengen = true),
        "IT" to CountryStampProfile(StampShape.CIRCLE, StampInk.TEAL, StampEmblem.COLOSSEUM, "INGRESSO", isSchengen = true),
        "ES" to CountryStampProfile(StampShape.OCTAGON, StampInk.RED, StampEmblem.SUN_ARCH, "ENTRADA", isSchengen = true),
        "NL" to CountryStampProfile(StampShape.OCTAGON, StampInk.INDIGO, StampEmblem.WINDMILL, "INREIS", isSchengen = true),
        "CH" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.RED, StampEmblem.SWISS_CROSS, "EINREISE", isSchengen = true),
        "AT" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.RED, StampEmblem.ALPINE_PEAKS, "EINREISE", isSchengen = true),
        "CZ" to CountryStampProfile(StampShape.OCTAGON, StampInk.INDIGO, StampEmblem.CASTLE_SPIRES, "VSTUP", isSchengen = true),
        "GR" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.TEAL, StampEmblem.PARTHENON, "ΕΙΣΟΔΟΣ", isSchengen = true),
        "PT" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.TEAL, StampEmblem.SUN_ARCH, "ENTRADA", isSchengen = true),
        "NO" to CountryStampProfile(StampShape.OVAL, StampInk.INDIGO, StampEmblem.ALPINE_PEAKS, "INNREISE", isSchengen = true),
        "SE" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.INDIGO, StampEmblem.THREE_CROWNS, "INRESA", isSchengen = true),
        "DK" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.RED, StampEmblem.THREE_CROWNS, "INDREJSE", isSchengen = true),
        "FI" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.TEAL, StampEmblem.ALPINE_PEAKS, "SAAPUMINEN", isSchengen = true),
        "IE" to CountryStampProfile(StampShape.CIRCLE, StampInk.TEAL, StampEmblem.SHAMROCK, "ENTRY • FÁILTE"),
        "IS" to CountryStampProfile(StampShape.OVAL, StampInk.TEAL, StampEmblem.VOLCANO_WAVE, "KOMA", isSchengen = true),
        "BE" to CountryStampProfile(StampShape.OCTAGON, StampInk.BLACK, StampEmblem.CROWN_SHIELD, "ENTRÉE", isSchengen = true),
        "HR" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.RED, StampEmblem.CASTLE_SPIRES, "ULAZAK", isSchengen = true),
        "HU" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.RED, StampEmblem.CASTLE_SPIRES, "BELÉPÉS", isSchengen = true),
        // North & South America
        "US" to CountryStampProfile(StampShape.OVAL, StampInk.RED, StampEmblem.LIBERTY_STAR, "ADMITTED"),
        "CA" to CountryStampProfile(StampShape.OVAL, StampInk.RED, StampEmblem.MAPLE_LEAF, "ADMITTED • ADMIS"),
        "MX" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.TEAL, StampEmblem.PYRAMID, "ENTRADA"),
        "BR" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.TEAL, StampEmblem.PALM_ISLAND, "ENTRADA"),
        "AR" to CountryStampProfile(StampShape.OVAL, StampInk.INDIGO, StampEmblem.SUN_ARCH, "INGRESO"),
        "PE" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.RED, StampEmblem.PYRAMID, "INGRESO"),
        "CL" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.INDIGO, StampEmblem.ALPINE_PEAKS, "ENTRADA"),
        // Asia-Pacific & Middle East
        "JP" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.BLACK, StampEmblem.TORII, "上陸許可"),
        "TW" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.INDIGO, StampEmblem.TAIPEI_SPIRE, "入境 • ENTRY"),
        "KR" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.PLUM, StampEmblem.TAEGEUK_GATE, "입국 • ENTRY"),
        "CN" to CountryStampProfile(StampShape.ROUNDED_RECT, StampInk.RED, StampEmblem.PAGODA, "入境 • ENTRY"),
        "HK" to CountryStampProfile(StampShape.OVAL, StampInk.INDIGO, StampEmblem.TAIPEI_SPIRE, "LANDED"),
        "SG" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.TEAL, StampEmblem.PALM_ISLAND, "VISIT PASS"),
        "TH" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.PLUM, StampEmblem.PAGODA, "ARRIVED"),
        "VN" to CountryStampProfile(StampShape.OCTAGON, StampInk.RED, StampEmblem.PAGODA, "NHẬP CẢNH"),
        "MY" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.INDIGO, StampEmblem.TAIPEI_SPIRE, "SOCIAL VISIT"),
        "ID" to CountryStampProfile(StampShape.OVAL, StampInk.TEAL, StampEmblem.VOLCANO_WAVE, "IMIGRASI"),
        "PH" to CountryStampProfile(StampShape.CIRCLE, StampInk.INDIGO, StampEmblem.PALM_ISLAND, "ADMITTED"),
        "IN" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.PLUM, StampEmblem.TAJ_DOME, "ARRIVED"),
        "TR" to CountryStampProfile(StampShape.OVAL, StampInk.RED, StampEmblem.CRESCENT_BRIDGE, "GİRİŞ"),
        "AE" to CountryStampProfile(StampShape.OCTAGON, StampInk.INDIGO, StampEmblem.BURJ_SPIRE, "ENTRY • دخول"),
        "QA" to CountryStampProfile(StampShape.OCTAGON, StampInk.PLUM, StampEmblem.BURJ_SPIRE, "ENTRY • دخول"),
        "SA" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.TEAL, StampEmblem.CRESCENT_BRIDGE, "دخول • ENTRY"),
        "AU" to CountryStampProfile(StampShape.CIRCLE, StampInk.TEAL, StampEmblem.OPERA_SAILS, "ARRIVED"),
        "NZ" to CountryStampProfile(StampShape.DOUBLE_RING, StampInk.BLACK, StampEmblem.SOUTHERN_CROSS, "PERMIT TO ENTER"),
        // Africa
        "EG" to CountryStampProfile(StampShape.SHIELD_ARCH, StampInk.INDIGO, StampEmblem.PYRAMID, "ARRIVAL • وصول"),
        "ZA" to CountryStampProfile(StampShape.CIRCLE, StampInk.TEAL, StampEmblem.SUN_ARCH, "ARRIVED"),
        "MA" to CountryStampProfile(StampShape.OCTAGON, StampInk.RED, StampEmblem.CRESCENT_BRIDGE, "ENTRÉE • دخول"),
    )

    fun fnv1a32(s: String): Int {
        var h = -0x7ee3623b // 0x811C9DC5
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    /**
     * Builds a country-specific, clearly fictional passport stamp style (shape, ink, landmark emblem,
     * local-language entry label, and mood) for a country ISO-2 or display name.
     */
    fun styleForCountry(
        isoOrName: String,
        continent: String?,
        isEarliest: Boolean,
        dayCount: Int,
    ): StampStyle {
        val norm = isoOrName.trim().uppercase(Locale.ROOT)
        val pos = fnv1a32(norm) and 0x7FFFFFFF
        val profile = countryProfiles[norm]

        val shape = profile?.shape ?: StampShape.entries[pos % StampShape.entries.size]
        val mood = when {
            isEarliest -> StampMood.FIRST_VISIT
            dayCount <= 1 -> StampMood.TRANSIT
            ((pos ushr 3) and 1) == 0 -> StampMood.ARRIVAL
            else -> StampMood.DEPARTURE
        }
        val ink = when {
            mood == StampMood.FIRST_VISIT -> StampInk.GOLD
            profile != null -> profile.ink
            else -> flatInks[(pos ushr 6) % flatInks.size]
        }
        val rotationDeg = ((pos ushr 10) and 0xFF) / 255f * 7.6f - 3.8f
        val wearSeed = (pos ushr 18) and 0xFFFF
        return StampStyle(
            shape = shape,
            mood = mood,
            ink = ink,
            continent = continent,
            rotationDeg = rotationDeg,
            wearSeed = wearSeed,
            emblem = profile?.emblem ?: StampEmblem.CONTINENT_FALLBACK,
            entryLabel = if (mood == StampMood.FIRST_VISIT && profile == null) mood.label else (profile?.entryLabel ?: mood.label),
            isoCode = norm.takeIf { it.length == 2 },
            isSchengen = profile?.isSchengen == true,
        )
    }
}
