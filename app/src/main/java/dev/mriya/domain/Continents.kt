package dev.mriya.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ISO-2 country code to continent name, from the bundled Natural Earth data (assets/continents.csv). */
object Continents {
    @Volatile private var cache: Map<String, String>? = null

    suspend fun get(ctx: Context): Map<String, String> = cache ?: withContext(Dispatchers.IO) {
        cache ?: ctx.applicationContext.assets.open("continents.csv").bufferedReader().useLines { lines ->
            lines.mapNotNull { l -> l.split(',').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
        }.also { cache = it }
    }
}
