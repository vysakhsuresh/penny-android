package app.penny.core.capture

import app.penny.core.money.CurrencyUnit
import app.penny.core.rules.RuleEngine
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pack-supplied notification template (CP-02). [regex] uses named groups: amount (required), payee, account,
 * currency. Matching is on "title\nbody". Templates are data in packs; no app is hard-coded in code.
 */
@Serializable
data class NotificationTemplate(
    val id: String,
    val packages: List<String>,
    val regex: String,
    val type: String = "expense",
    val currency: String? = null,
    /** Amounts in this template use this locale's separators ("de-DE": 1.234,56). */
    val numberLocale: String = "en",
    val confidence: Int = 90,
)

data class ParsedCapture(
    val amountMinor: Long,
    val currency: String,
    val income: Boolean,
    val payee: String?,
    val account: String?,
    val confidence: Int,
    val templateId: String?,
    val date: LocalDate? = null,
    val tax: Long? = null,
    val lines: List<Pair<String, Long>> = emptyList(),
)

object AmountText {
    /** Common currency markers to ISO code; packs provide the default when none is seen. */
    val SYMBOLS = linkedMapOf(
        "₹" to "INR", "rs." to "INR", "rs" to "INR", "inr" to "INR", "₱" to "PHP", "php" to "PHP", "$" to "USD", "usd" to "USD",
        "£" to "GBP", "gbp" to "GBP", "€" to "EUR", "eur" to "EUR", "r$" to "BRL", "brl" to "BRL", "aed" to "AED", "د.إ" to "AED",
        "¥" to "JPY", "jpy" to "JPY", "rp" to "IDR", "idr" to "IDR", "₦" to "NGN", "ngn" to "NGN", "a$" to "AUD", "aud" to "AUD",
        "s$" to "SGD", "sgd" to "SGD", "mxn" to "MXN", "ksh" to "KES", "kes" to "KES", "৳" to "BDT", "tk" to "BDT", "bdt" to "BDT",
        "₩" to "KRW", "krw" to "KRW", "₫" to "VND", "vnd" to "VND", "฿" to "THB", "thb" to "THB", "rm" to "MYR", "myr" to "MYR",
        "zł" to "PLN", "pln" to "PLN", "₺" to "TRY", "try" to "TRY", "zar" to "ZAR", "r" to "ZAR", "cad" to "CAD", "c$" to "CAD",
        "chf" to "CHF", "sar" to "SAR", "egp" to "EGP", "pkr" to "PKR", "lkr" to "LKR", "npr" to "NPR", "ghs" to "GHS", "kwd" to "KWD",
    )

    /** Parses "1,234.56", "1.234,56", "1 234,56", "12,34,567.00" (lakh) to minor units of [currency]. */
    fun toMinor(raw: String, currency: String): Long? {
        var s = raw.trim().replace(" ", " ").replace(" ", "").replace("'", "")
        if (s.isEmpty()) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimals = CurrencyUnit.of(currency).minorUnits
        s = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) s.replace(",", "") else s.replace(".", "").replace(',', '.')
            lastComma >= 0 -> {
                val tail = s.length - lastComma - 1
                if (tail in 1..2 || (tail == 3 && decimals == 3 && s.count { it == ',' } == 1)) s.replace(',', '.') else s.replace(",", "")
            }
            lastDot >= 0 -> {
                val tail = s.length - lastDot - 1
                if (s.count { it == '.' } > 1 || (tail == 3 && decimals != 3)) s.replace(".", "") else s
            }
            else -> s
        }
        val v = s.toBigDecimalOrNull() ?: return null
        if (v.signum() <= 0) return null
        return v.setScale(decimals, RoundingMode.HALF_EVEN).unscaledValue().toLong()
    }

    private val amountRegex = Regex(
        "(?i)(₹|rs\\.?|inr|₱|php|r\\$|a\\$|s\\$|c\\$|\\$|usd|£|gbp|€|eur|brl|aed|¥|jpy|rp|idr|₦|ngn|aud|sgd|mxn|ksh|kes|৳|tk|bdt|₩|krw|₫|vnd|฿|thb|rm|myr|zł|pln|₺|try|zar|cad|chf|sar|egp|pkr|lkr|npr|ghs|kwd)?\\s?([0-9][0-9.,'  ]*[0-9]|[0-9])\\s?(₹|inr|php|usd|eur|€|gbp|brl|aed|jpy|idr|ngn|aud|sgd|mxn|kes|bdt|krw|vnd|₫|thb|myr|zł|pln|try|zar|cad|chf|sar|egp|pkr|lkr|npr|ghs|kwd)?",
    )

    data class Found(val minor: Long, val currency: String, val start: Int, val hadMarker: Boolean)

    fun find(text: String, defaultCurrency: String): List<Found> = amountRegex.findAll(text).mapNotNull { m ->
        val marker = (m.groupValues[1].ifEmpty { m.groupValues[3] }).lowercase().trimEnd('.')
        val cur = SYMBOLS[marker] ?: SYMBOLS[marker.removeSuffix(".")] ?: defaultCurrency
        // Without a currency marker, ignore numbers that look like times, years, phone numbers or reference ids.
        val digits = m.groupValues[2].filter(Char::isDigit)
        if (marker.isEmpty() && (digits.length > 9 || text.getOrNull(m.range.last + 1) == ':' || text.getOrNull(m.range.first - 1) == ':')) return@mapNotNull null
        toMinor(m.groupValues[2], cur)?.let { Found(it, cur, m.range.first, marker.isNotEmpty()) }
    }.toList()
}

object NotificationParser {
    private val OUT = listOf("paid", "sent", "debited", "spent", "purchase", "payment to", "you paid", "transferred to", "withdrawn", "charged",
        "pagaste", "enviaste", "pago", "bayad", "binayaran", "dibayar", "bezahlt", "überwiesen", "payé", "pagou", "compra", "débito", "خصم", "支付")
    private val IN = listOf("received", "credited", "deposited", "refund", "cashback received", "you got", "recibiste", "natanggap",
        "diterima", "erhalten", "reçu", "recebeu", "crédito", "إيداع", "收到")

    fun parse(title: String, body: String, pkg: String, templates: List<NotificationTemplate>, defaultCurrency: String): ParsedCapture? {
        val text = "$title\n$body"
        for (t in templates.filter { pkg in it.packages || it.packages.isEmpty() }) {
            val m = runCatching { Regex(t.regex, setOf(RegexOption.IGNORE_CASE)).find(text) }.getOrNull() ?: continue
            val g = m.groups as? MatchNamedGroupCollection ?: continue
            val cur = runCatching { g["currency"]?.value }.getOrNull()?.lowercase()?.let { AmountText.SYMBOLS[it] } ?: t.currency ?: defaultCurrency
            val amt = runCatching { g["amount"]?.value }.getOrNull()?.let { AmountText.toMinor(it, cur) } ?: continue
            val payee = runCatching { g["payee"]?.value }.getOrNull()?.trim()?.trimEnd('.', ',')
            val acct = runCatching { g["account"]?.value }.getOrNull()?.trim()
            return ParsedCapture(amt, cur, t.type == "income", payee, acct, t.confidence, t.id)
        }
        return generic(text, defaultCurrency)
    }

    /** Fallback for apps without a template: needs a direction keyword and a marked amount; confidence is lower. */
    fun generic(text: String, defaultCurrency: String): ParsedCapture? {
        val low = RuleEngine.normalise(text)
        val outAt = OUT.mapNotNull { k -> low.indexOf(RuleEngine.normalise(k)).takeIf { it >= 0 } }.minOrNull()
        val inAt = IN.mapNotNull { k -> low.indexOf(RuleEngine.normalise(k)).takeIf { it >= 0 } }.minOrNull()
        if (outAt == null && inAt == null) return null
        if (Regex("(?i)\\b(otp|one[- ]time password|verification code|offer|win|cashback up to|apply now|loan)\\b").containsMatchIn(text) &&
            !Regex("(?i)\\b(debited|credited|paid|received)\\b").containsMatchIn(text)
        ) return null
        val found = AmountText.find(text, defaultCurrency)
        val amount = found.firstOrNull { it.hadMarker } ?: return null
        val income = inAt != null && (outAt == null || inAt < outAt)
        // UPI messages name the other side by a VPA ("to VPA swiggy@icici"): its handle is the clearest payee there is.
        val vpa = if (Regex("(?i)\\b(upi|vpa)\\b").containsMatchIn(text))
            Regex("(?i)\\b([a-z][a-z0-9._-]{1,40})@[a-z]{2,}\\b").find(text)?.groupValues?.get(1)
                ?.replace(Regex("[0-9]+$"), "")?.split(Regex("[._-]+"))?.filter { it.isNotBlank() }
                ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }?.takeIf { it.length >= 2 }
        else null
        val payee = vpa ?: Regex("(?i)\\b(?:to|at|from|paid to|sent to|received from|para|a|kay|ke|an)\\s+([\\p{L}0-9&'.\\- ]{2,40}?)(?=\\s+(?:on|via|using|for|ref|upi|with|from|at)\\b|[.,\\n]|$)")
            .find(text)?.groupValues?.get(1)?.trim()
        val acct = Regex("(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?|ending|xx|\\*+)?\\s*([0-9xX*]{2,}[0-9]{2,4})").find(text)?.groupValues?.get(1)
        return ParsedCapture(amount.minor, amount.currency, income, payee?.takeIf { it.isNotBlank() }, acct, 60, null)
    }
}

/** CP-07: turns OCR text into total, tax, date, merchant and line items. Works on any script; keywords cover major languages. */
object ReceiptParser {
    private val TOTAL = Regex("(?i)(grand\\s*total|total\\s*due|amount\\s*due|total\\s*amount|net\\s*amount|balance\\s*due|to\\s*pay|total|totale|gesamt|summe|importe|monto|valor\\s*total|kabuuan|jumlah|合計|合计|총액|tổng|รวม|المجموع|סה\"כ|итого|toplam)")
    private val TAX = Regex("(?i)(gst|vat|tax|mwst|iva|tva|ppn|sst|cgst|sgst|igst|消費税|부가세)")
    private val DATE_PATTERNS = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yy", "yyyy/MM/dd", "dd MMM yyyy", "MMM dd, yyyy")

    fun parse(text: String, defaultCurrency: String, today: LocalDate = LocalDate.now()): ParsedCapture? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        var total: AmountText.Found? = null
        var tax: Long? = null
        for (l in lines) {
            val amounts = AmountText.find(l, defaultCurrency)
            if (amounts.isEmpty()) continue
            if (TOTAL.containsMatchIn(l) && !Regex("(?i)sub\\s*total|subtotal|zwischensumme").containsMatchIn(l)) {
                val a = amounts.last()
                if (total == null || a.minor >= total.minor) total = a
            } else if (TAX.containsMatchIn(l)) {
                tax = (tax ?: 0) + amounts.last().minor
            }
        }
        val all = lines.flatMap { AmountText.find(it, defaultCurrency) }
        val chosen = total ?: all.maxByOrNull { it.minor } ?: return null
        val merchant = lines.take(5).firstOrNull { l -> l.count(Char::isLetter) >= 3 && !Regex("(?i)(receipt|invoice|tax|bill|welcome|tel|phone|gstin|www|http)").containsMatchIn(l) }
        val date = lines.firstNotNullOfOrNull { l -> findDate(l, today) }
        val items = lines.mapNotNull { l ->
            if (TOTAL.containsMatchIn(l) || TAX.containsMatchIn(l)) return@mapNotNull null
            val m = Regex("^(.{3,40}?)\\s{1,}([0-9][0-9.,]*[0-9])$").find(l) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            if (name.count(Char::isLetter) < 2) return@mapNotNull null
            AmountText.toMinor(m.groupValues[2], chosen.currency)?.let { name to it }
        }.filter { it.second < chosen.minor }
        val confidence = (if (total != null) 50 else 25) + (if (merchant != null) 20 else 0) + (if (date != null) 20 else 0)
        return ParsedCapture(chosen.minor, chosen.currency, false, merchant, null, confidence, "receipt", date, tax, items)
    }

    fun findDate(line: String, today: LocalDate): LocalDate? {
        val cands = Regex("(\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{1,2} [A-Za-z]{3} \\d{4}|[A-Za-z]{3} \\d{1,2}, \\d{4})").findAll(line).map { it.value }
        for (c in cands) for (p in DATE_PATTERNS) {
            val d = runCatching { LocalDate.parse(c, DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH)) }.getOrNull() ?: continue
            if (!d.isAfter(today.plusDays(1)) && d.isAfter(today.minusYears(5))) return d
        }
        return null
    }
}

/** CP-08 voice: what the grammar understood, for the user to confirm. */
data class VoiceResult(
    val amountMinor: Long?,
    val currency: String,
    val income: Boolean,
    val payee: String?,
    val categoryId: String?,
    val accountId: String?,
    val date: LocalDate,
    val people: List<String>,
    val note: String,
)

/**
 * CP-08 grammar over recognised speech (any language the recogniser supports). Keywords cover English plus common
 * mixed-speech pairs (Hinglish, Taglish, Spanglish, Arabizi). Number words are handled for English and Hindi.
 */
class VoiceGrammar(
    private val currency: String,
    private val today: LocalDate,
    private val categories: Map<String, String>,
    private val accounts: Map<String, String>,
) {
    private val incomeWords = listOf("received", "got paid", "salary", "income", "earned", "mila", "mile", "natanggap", "sweldo", "recibí", "cobré", "استلمت")
    private val yesterday = listOf("yesterday", "kal", "kahapon", "ayer", "ams", "امس")
    private val dayBefore = listOf("day before yesterday", "parso", "noong isang araw", "anteayer")
    private val numberWords = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L,
        "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "fifteen" to 15L, "twenty" to 20L, "thirty" to 30L, "forty" to 40L,
        "fifty" to 50L, "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
        "ek" to 1L, "do" to 2L, "teen" to 3L, "char" to 4L, "paanch" to 5L, "das" to 10L, "bees" to 20L, "pachas" to 50L, "sau" to 100L,
    )
    private val multipliers = mapOf("hundred" to 100L, "sau" to 100L, "thousand" to 1_000L, "hazaar" to 1_000L, "hazar" to 1_000L, "k" to 1_000L,
        "lakh" to 100_000L, "lac" to 100_000L, "million" to 1_000_000L, "crore" to 10_000_000L, "libo" to 1_000L, "mil" to 1_000L)

    fun parse(speech: String): VoiceResult {
        val low = RuleEngine.normalise(speech)
        val income = incomeWords.any { low.contains(it) }
        val date = when {
            dayBefore.any { low.contains(it) } -> today.minusDays(2)
            yesterday.any { Regex("\\b$it\\b").containsMatchIn(low) } -> today.minusDays(1)
            else -> today
        }
        val amount = digitsAmount(low) ?: wordsAmount(low)
        val cat = categories.entries.sortedByDescending { it.key.length }.firstOrNull { low.contains(RuleEngine.normalise(it.key)) }?.value
        val acct = accounts.entries.sortedByDescending { it.key.length }.firstOrNull { low.contains(RuleEngine.normalise(it.key)) }?.value
        val payee = Regex("\\b(?:at|from|to|sa|mein|me|en|في)\\s+([\\p{L}0-9'&\\- ]{2,30}?)(?=\\s+(?:for|on|with|yesterday|today|using|from|split|kal|kahapon|ayer)\\b|$)")
            .find(low)?.groupValues?.get(1)?.trim()?.takeIf { p -> accounts.keys.none { RuleEngine.normalise(it) == p } }
            ?.replaceFirstChar { it.uppercase() }
        val people = Regex("\\b(?:split with|with|kasama si|ke saath|con)\\s+([\\p{L} ,and&]+)$").find(low)?.groupValues?.get(1)
            ?.split(",", " and ", " at ", " & ", " y ")?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.replaceFirstChar(Char::uppercase) }.orEmpty()
        return VoiceResult(amount, currency, income, payee, cat, acct, date, people, speech.trim())
    }

    private fun digitsAmount(low: String): Long? {
        val m = Regex("([0-9][0-9.,]*)\\s*(k|lakh|lac|thousand|hazaar|hazar|crore|million)?").find(low) ?: return null
        val base = AmountText.toMinor(m.groupValues[1], currency) ?: return null
        val mult = multipliers[m.groupValues[2]] ?: 1L
        return base * mult
    }

    private fun wordsAmount(low: String): Long? {
        var total = 0L
        var current = 0L
        var any = false
        for (w in low.split(Regex("[\\s-]+"))) {
            val n = numberWords[w]
            val m = multipliers[w]
            when {
                m != null && (any || n != null) -> {
                    if (w == "sau" && current == 0L) current = 1
                    current = maxOf(current, 1) * m; if (m >= 1_000) { total += current; current = 0 }; any = true
                }
                n != null -> { current += n; any = true }
            }
        }
        if (!any) return null
        val major = total + current
        val decimals = CurrencyUnit.of(currency).minorUnits
        return BigDecimal(major).movePointRight(decimals).toLong()
    }
}
