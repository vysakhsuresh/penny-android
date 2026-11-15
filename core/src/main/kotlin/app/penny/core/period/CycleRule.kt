package app.penny.core.period

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/** Day-of-month values are 1..31; 31 means "last day of the month" because it is clamped (Feb 31 -> Feb 28/29). */
const val LAST_DAY_OF_MONTH = 31

/** The profile's budget cycle (BU-01). Serialised into profile settings and pack files. */
@Serializable
sealed interface CycleRule {
    @Serializable
    @SerialName("monthly")
    data class Monthly(val startDay: Int = 1) : CycleRule {
        init { requireDay(startDay) }
    }

    /** Two start days per month, e.g. 15 and the last day (quincena, kinsenas). */
    @Serializable
    @SerialName("semimonthly")
    data class SemiMonthly(val firstDay: Int = 15, val secondDay: Int = LAST_DAY_OF_MONTH) : CycleRule {
        init { requireDay(firstDay); requireDay(secondDay) }
    }

    @Serializable
    @SerialName("weekly")
    data class Weekly(val startsOn: DayOfWeek = DayOfWeek.MONDAY) : CycleRule

    @Serializable
    @SerialName("biweekly")
    data class Biweekly(@Serializable(with = LocalDateIsoSerializer::class) val anchor: LocalDate) : CycleRule

    @Serializable
    @SerialName("fourweekly")
    data class FourWeekly(@Serializable(with = LocalDateIsoSerializer::class) val anchor: LocalDate) : CycleRule

    /** Quarters counted from the fiscal year start month and day. */
    @Serializable
    @SerialName("quarterly")
    data class Quarterly(val fiscalMonth: Int = 1, val fiscalDay: Int = 1) : CycleRule {
        init { require(fiscalMonth in 1..12); requireDay(fiscalDay) }
    }

    @Serializable
    @SerialName("yearly")
    data class Yearly(val fiscalMonth: Int = 1, val fiscalDay: Int = 1) : CycleRule {
        init { require(fiscalMonth in 1..12); requireDay(fiscalDay) }
    }

    /**
     * "When money lands": a period starts on each date an income at or above [thresholdMinor] posts.
     * If no income lands for [maxDays] days a new period starts anyway, so every date belongs to exactly one
     * period (DECISIONS.md 2026-11-15).
     */
    @Serializable
    @SerialName("onincome")
    data class OnIncome(val thresholdMinor: Long = 0, val maxDays: Int = 31) : CycleRule {
        init { require(thresholdMinor >= 0); require(maxDays in 7..62) }
    }
}

private fun requireDay(day: Int) = require(day in 1..LAST_DAY_OF_MONTH) { "Day of month must be 1..31: $day" }
