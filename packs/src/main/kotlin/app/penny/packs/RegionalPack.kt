package app.penny.packs

import app.penny.core.event.PennyJson
import app.penny.core.money.Grouping
import app.penny.core.period.CycleRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** Regional pack (SPEC §5.9, Appendix B). Data only: adding a region is a JSON file, never code. */
@Serializable
data class RegionalPack(
    val schema: Int = 1,
    val id: String,
    val name: String,
    val locales: List<String>,
    val languages: List<String> = emptyList(),
    val currency: String,
    @SerialName("number_grouping") val numberGrouping: String = "standard",
    @SerialName("pay_cycle_default") val payCycleDefault: CycleRule,
    @SerialName("first_day_of_week") val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    @SerialName("fiscal_year_start") val fiscalYearStart: String = "01-01",
    val timezone: String = "UTC",
    val holidays: List<Holiday> = emptyList(),
    @SerialName("festival_windows") val festivalWindows: List<FestivalWindow> = emptyList(),
    @SerialName("circle_names") val circleNames: List<String> = emptyList(),
    @SerialName("payment_apps") val paymentApps: List<PaymentApp> = emptyList(),
    @SerialName("bank_apps") val bankApps: List<PaymentApp> = emptyList(),
    @SerialName("aggregator_provider") val aggregatorProvider: String? = null,
    @SerialName("settlement_schemes") val settlementSchemes: List<String> = emptyList(),
    @SerialName("typical_amount_chips") val typicalAmountChips: List<Long> = emptyList(),
    @SerialName("cancellation_guides") val cancellationGuides: List<String> = emptyList(),
    @SerialName("price_points") val pricePoints: PricePoints? = null,
    @SerialName("privacy_text_ref") val privacyTextRef: String? = null,
) {
    val primaryLocale: String get() = locales.first()
    val grouping: Grouping get() = if (numberGrouping.equals("indian", ignoreCase = true)) Grouping.INDIAN else Grouping.STANDARD
}

@Serializable data class Holiday(val name: String, val date: String)
@Serializable data class FestivalWindow(val name: String, val month: Int)
@Serializable
data class PaymentApp(
    val name: String,
    @SerialName("package") val packageName: String? = null,
    @SerialName("parser_id") val parserId: String? = null,
)
@Serializable
data class PricePoints(
    @SerialName("plus_monthly") val monthly: Long,
    @SerialName("plus_yearly") val yearly: Long,
    @SerialName("plus_lifetime") val lifetime: Long,
)

object PackParser {
    const val GENERIC_ID = "XX"
    fun parse(json: String): RegionalPack = PennyJson.decodeFromString(RegionalPack.serializer(), json)
}
