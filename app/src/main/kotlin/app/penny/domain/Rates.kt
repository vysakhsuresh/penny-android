package app.penny.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.penny.core.event.PennyJson
import app.penny.core.model.FxRate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AC-04 daily reference rates, cached on the device for 400 days. Source: the European Central Bank's public
 * reference rates (EUR base; crosses are derived). Only public data is requested; nothing about the user is sent.
 * Rates are device-local cache, not user data, so they never enter the event log or sync.
 */
@Singleton
class RatesCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val file get() = File(context.filesDir, "rates.json")
    private val ser = ListSerializer(FxRate.serializer())
    private val _rates = MutableStateFlow(read())
    val rates: StateFlow<List<FxRate>> = _rates

    private fun read(): List<FxRate> = runCatching { PennyJson.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    fun merge(fresh: List<FxRate>) {
        val cutoff = LocalDate.now().minusDays(400)
        val all = (_rates.value + fresh).filter { !it.date.isBefore(cutoff) }.distinctBy { Triple(it.from, it.to, it.date) }
        runCatching { file.writeText(PennyJson.encodeToString(ser, all)) }
        _rates.value = all
    }

    val lastDate: LocalDate? get() = _rates.value.maxOfOrNull { it.date }

    /** Parses the ECB XML (`<Cube time="…"><Cube currency="USD" rate="1.08"/>…`). */
    fun parseEcb(xml: String): List<FxRate> {
        val out = mutableListOf<FxRate>()
        var date: LocalDate? = null
        Regex("<Cube\\s+(time=\"([0-9-]{10})\"|currency=\"([A-Z]{3})\"\\s+rate=\"([0-9.]+)\")").findAll(xml).forEach { m ->
            if (m.groupValues[2].isNotEmpty()) date = LocalDate.parse(m.groupValues[2])
            else date?.let { d -> out += FxRate("EUR", m.groupValues[3], d, m.groupValues[4], manual = false) }
        }
        return out
    }

    suspend fun refresh(days90: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(if (days90) ECB_90 else ECB_DAILY)
            val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("User-Agent", "Penny/1 (rates)") }
            val xml = c.inputStream.bufferedReader().use { it.readText() }
            merge(parseEcb(xml))
            true
        }.getOrDefault(false)
    }

    companion object {
        const val ECB_DAILY = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        const val ECB_90 = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"
    }
}

@HiltWorker
class RatesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cache: RatesCache,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (cache.refresh(cache.lastDate == null || cache.lastDate!!.isBefore(LocalDate.now().minusDays(5)))) Result.success() else Result.retry()

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "rates", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RatesWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build(),
            )
        }
    }
}
