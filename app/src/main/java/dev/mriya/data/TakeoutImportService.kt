package dev.mriya.data

import android.content.Context
import android.net.Uri
import dev.mriya.domain.AirportDb
import dev.mriya.domain.CountryShapes
import dev.mriya.domain.OfflineCountryLookup
import dev.mriya.domain.takeout.TakeoutArchive
import dev.mriya.domain.takeout.TakeoutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Streams a user-picked Google Takeout `.zip` or `.json` file on a worker thread and returns
 * only the derived country stamps and flights (raw location points are never stored).
 */
class TakeoutImportService(context: Context) {
    private val ctx = context.applicationContext

    suspend fun importUri(
        uri: Uri,
        onProgress: (points: Long, files: Int) -> Unit = { _, _ -> },
    ): TakeoutData = withContext(Dispatchers.Default) {
        val lookup = OfflineCountryLookup(CountryShapes.get(ctx))
        val airports = AirportDb.get(ctx)
        val engine = TakeoutEngine(
            isoAt = lookup::isoAt,
            airportNear = { airports.nearest(it, 25.0)?.iata },
            airportCountry = { airports.nearest(it, 40.0)?.country?.takeIf { c -> c.length == 2 } },
        )
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                TakeoutArchive.streamTakeout(
                    input = input,
                    engine = engine,
                    onProgress = onProgress,
                    isCancelled = { !coroutineContext.isActive },
                )
            } ?: error("Could not open the selected file.")
        }
        engine.result(Instant.now().epochSecond)
    }
}
