package app.penny.ui.common

import android.content.Context
import app.penny.R
import app.penny.core.model.Profile
import app.penny.core.model.TxType
import app.penny.core.money.CurrencySymbols
import app.penny.core.money.MoneyFormatter
import app.penny.core.money.NumberFormatSpec
import app.penny.core.period.CycleRule
import app.penny.core.period.LAST_DAY_OF_MONTH
import app.penny.packs.RegionalPack
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Money display for one profile: locale separators, pack grouping (or the user's override). */
class MoneyFmt(val spec: NumberFormatSpec) {
    /** Wrapped in a first-strong isolate so amounts with RTL symbols never break surrounding LTR text, and vice versa (LO-07). */
    fun format(minor: Long, currency: String, signed: Boolean = false): String {
        val text = MoneyFormatter.format(minor, currency, spec, symbol = CurrencySymbols.symbol(currency, spec.localeTag), signForPositive = signed)
        return FSI + text + PDI
    }

    fun editable(minor: Long, currency: String) = MoneyFormatter.toEditable(minor, currency, spec)

    companion object {
        /** First-strong isolate and pop directional isolate (Unicode bidi), built from code points. */
        private val FSI = Char(0x2068).toString()
        private val PDI = Char(0x2069).toString()

        fun of(profile: Profile, pack: RegionalPack) =
            MoneyFmt(NumberFormatSpec.forLocale(profile.localeTag, profile.groupingOverride ?: pack.grouping))

        fun of(pack: RegionalPack) = MoneyFmt(NumberFormatSpec.forLocale(pack.primaryLocale, pack.grouping))
    }
}

/** UI locale for dates follows the device language (ST-10), not the region. */
fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

/** Short localised month and day ("Nov 6", "6 nov.", "11月6日") for chart labels. */
fun monthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(date)

fun relativeDate(context: Context, date: LocalDate, today: LocalDate): String = when (date) {
    today -> context.getString(R.string.today)
    today.minusDays(1) -> context.getString(R.string.yesterday)
    else -> shortDate(date)
}

fun cycleLabel(context: Context, rule: CycleRule): String = when (rule) {
    is CycleRule.Monthly ->
        if (rule.startDay == LAST_DAY_OF_MONTH) context.getString(R.string.cycle_monthly_last)
        else context.getString(R.string.cycle_monthly, rule.startDay)
    is CycleRule.SemiMonthly -> context.getString(R.string.cycle_semimonthly, rule.firstDay)
    is CycleRule.Weekly -> context.getString(R.string.cycle_weekly)
    is CycleRule.Biweekly -> context.getString(R.string.cycle_biweekly)
    is CycleRule.FourWeekly -> context.getString(R.string.cycle_fourweekly)
    is CycleRule.Quarterly -> context.getString(R.string.cycle_quarterly)
    is CycleRule.Yearly -> context.getString(R.string.cycle_yearly)
    is CycleRule.OnIncome -> context.getString(R.string.cycle_onincome)
}

fun typeLabel(type: TxType): Int = when (type) {
    TxType.EXPENSE -> R.string.type_expense
    TxType.INCOME -> R.string.type_income
    TxType.TRANSFER -> R.string.type_transfer
    TxType.REFUND -> R.string.type_refund
    TxType.ADJUSTMENT -> R.string.type_adjustment
    TxType.OPENING_BALANCE -> R.string.type_opening
}
