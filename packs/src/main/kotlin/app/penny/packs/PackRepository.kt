package app.penny.packs

import android.content.res.AssetManager

/** Loads every pack from `assets/packs/`. Unknown regions fall back to the generic pack. */
class PackRepository(private val sources: () -> Map<String, String>) {
    private val packs: Map<String, RegionalPack> by lazy {
        sources().values.map(PackParser::parse).associateBy { it.id }
    }

    fun all(): List<RegionalPack> = packs.values.sortedWith(compareBy({ it.id == PackParser.GENERIC_ID }, { it.name }))
    fun get(id: String): RegionalPack = packs[id] ?: packs.getValue(PackParser.GENERIC_ID)

    /** Picks the pack whose locale country matches the device, else generic (OB-01 defaults from device locale). */
    fun forCountry(country: String?): RegionalPack =
        packs.values.firstOrNull { p -> p.id.equals(country, true) || p.locales.any { it.substringAfter('-').equals(country, true) } }
            ?: get(PackParser.GENERIC_ID)

    companion object {
        const val DIR = "packs"
        fun fromAssets(assets: AssetManager) = PackRepository {
            assets.list(DIR).orEmpty().filter { it.endsWith(".json") }
                .associateWith { name -> assets.open("$DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }
        }
    }
}
