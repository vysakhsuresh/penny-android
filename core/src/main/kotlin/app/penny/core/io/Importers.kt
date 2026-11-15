package app.penny.core.io

import app.penny.core.money.AmountCalculator
import app.penny.core.money.AmountResult
import app.penny.core.money.CurrencyUnit
import app.penny.core.money.NumberFormatSpec
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/** A row read from any importer, before it becomes a transaction. Positive = money in, negative = money out. */
data class ImportedRow(
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val payee: String,
    val note: String = "",
    val category: String = "",
    val account: String = "",
    val tags: List<String> = emptyList(),
    val externalId: String,
    /** Set when the source marks the row as a transfer (e.g. YNAB "Transfer : Savings"). */
    val transferAccount: String? = null,
)

/**
 * CP-09 column mapper. Column indexes are 0-based; null means "not in this file". When [amountCol] is null the file has
 * separate [debitCol]/[creditCol] columns. Saved per bank as presets.
 */
@Serializable
data class CsvMapping(
    val name: String,
    val dateCol: Int,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val payeeCol: Int? = null,
    val noteCol: Int? = null,
    val categoryCol: Int? = null,
    val accountCol: Int? = null,
    val tagsCol: Int? = null,
    val currencyCol: Int? = null,
    val datePattern: String = "yyyy-MM-dd",
    /** Locale tag whose separators the amounts use (e.g. "de-DE" for 1.234,56). */
    val numberLocale: String = "en-US",
    val hasHeader: Boolean = true,
    /** Some banks show spend as positive numbers: flip the sign. */
    val invertSign: Boolean = false,
    val skipRows: Int = 0,
)

data class ImportResult(val rows: List<ImportedRow>, val errors: List<String>)

object Importers {
    fun hash(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(parts.joinToString("|").toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    fun parseAmount(raw: String, currency: String, spec: NumberFormatSpec): Long? {
        var s = raw.trim().replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        var neg = false
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.drop(1).dropLast(1) }
        if (s.endsWith("-")) { neg = true; s = s.dropLast(1) }
        if (s.startsWith("-") || s.startsWith("−")) { neg = true; s = s.drop(1) }
        s = s.replace(Regex("[^0-9.,']"), "")
        if (s.isEmpty()) return null
        val unit = CurrencyUnit.of(currency)
        val v = when (val r = AmountCalculator.evaluate(s, unit, spec)) {
            is AmountResult.Ok -> r.minor
            is AmountResult.Invalid -> {
                // Fallback: decimals beyond the currency are rounded (bank exports with 4 decimals).
                val norm = s.replace(spec.groupSeparator.toString(), "").replace(spec.decimalSeparator, '.')
                norm.toBigDecimalOrNull()?.setScale(unit.minorUnits, RoundingMode.HALF_EVEN)?.unscaledValue()?.toLong() ?: return null
            }
        }
        return if (neg) -v else v
    }

    private fun parseDate(raw: String, pattern: String): LocalDate? = try {
        LocalDate.parse(raw.trim().take(pattern.length + 4).trim(), DateTimeFormatter.ofPattern(pattern))
    } catch (_: DateTimeParseException) {
        runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()
    }

    fun csv(text: String, m: CsvMapping, defaultCurrency: String): ImportResult {
        val spec = NumberFormatSpec.forLocale(m.numberLocale)
        val all = Csv.parse(text).drop(m.skipRows)
        val data = if (m.hasHeader) all.drop(1) else all
        val out = mutableListOf<ImportedRow>()
        val errors = mutableListOf<String>()
        data.forEachIndexed { i, r ->
            fun col(c: Int?) = c?.let { r.getOrNull(it) }.orEmpty().trim()
            val date = parseDate(col(m.dateCol), m.datePattern)
            val cur = col(m.currencyCol).uppercase().ifEmpty { defaultCurrency }
            val amount = if (m.amountCol != null) parseAmount(col(m.amountCol), cur, spec)
            else {
                val d = parseAmount(col(m.debitCol), cur, spec)
                val c = parseAmount(col(m.creditCol), cur, spec)
                if (d == null && c == null) null else (c ?: 0) - kotlin.math.abs(d ?: 0)
            }
            if (date == null || amount == null) { errors += "Row ${i + 1}"; return@forEachIndexed }
            val signed = if (m.invertSign) -amount else amount
            val payee = col(m.payeeCol)
            out += ImportedRow(
                date, signed, cur, payee, col(m.noteCol), col(m.categoryCol), col(m.accountCol),
                col(m.tagsCol).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                externalId = "csv:" + hash(m.name, date.toString(), signed.toString(), payee, col(m.noteCol), i.toString()),
            )
        }
        return ImportResult(out, errors)
    }

    /** OFX/QFX (SGML or XML): STMTTRN blocks with DTPOSTED, TRNAMT, NAME/PAYEE, MEMO, FITID; CURDEF for currency. */
    fun ofx(text: String, defaultCurrency: String): ImportResult {
        val cur = Regex("<CURDEF>\\s*([A-Z]{3})").find(text)?.groupValues?.get(1) ?: defaultCurrency
        val out = mutableListOf<ImportedRow>()
        val errors = mutableListOf<String>()
        Regex("<STMTTRN>(.*?)</STMTTRN>", RegexOption.DOT_MATCHES_ALL).findAll(text).forEachIndexed { i, mr ->
            val b = mr.groupValues[1]
            fun tag(t: String) = Regex("<$t>\\s*([^<\\r\\n]*)").find(b)?.groupValues?.get(1)?.trim().orEmpty()
            val date = runCatching { LocalDate.parse(tag("DTPOSTED").take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            val amt = tag("TRNAMT").toBigDecimalOrNull()
            if (date == null || amt == null) { errors += "Entry ${i + 1}"; return@forEachIndexed }
            val minor = amt.setScale(CurrencyUnit.of(cur).minorUnits, RoundingMode.HALF_EVEN).unscaledValue().toLong()
            val fit = tag("FITID").ifEmpty { hash(date.toString(), minor.toString(), tag("NAME"), i.toString()) }
            out += ImportedRow(date, minor, cur, tag("NAME").ifEmpty { tag("PAYEE") }, tag("MEMO"), externalId = "ofx:$fit")
        }
        return ImportResult(out, errors)
    }

    /** QIF: D date, T amount, P payee, M memo, L category ("[Account]" = transfer), ^ ends a record. */
    fun qif(text: String, defaultCurrency: String, datePattern: String = "M/d/yyyy"): ImportResult {
        val out = mutableListOf<ImportedRow>()
        val errors = mutableListOf<String>()
        val rec = mutableMapOf<Char, String>()
        var n = 0
        val spec = NumberFormatSpec.forLocale("en-US")
        fun flush() {
            if (rec.isEmpty()) return
            n++
            val d = rec['D']?.replace('\'', '/')?.replace(' ', '0')
            val date = d?.let { s -> listOf(datePattern, "M/d/yy", "d/M/yyyy", "yyyy-MM-dd").firstNotNullOfOrNull { p -> runCatching { LocalDate.parse(s, DateTimeFormatter.ofPattern(p)) }.getOrNull() } }
            val amt = rec['T']?.let { parseAmount(it, defaultCurrency, spec) }
            if (date == null || amt == null) errors += "Record $n" else {
                val l = rec['L'].orEmpty()
                val transfer = if (l.startsWith("[") && l.endsWith("]")) l.drop(1).dropLast(1) else null
                out += ImportedRow(date, amt, defaultCurrency, rec['P'].orEmpty(), rec['M'].orEmpty(), if (transfer == null) l else "",
                    externalId = "qif:" + hash(date.toString(), amt.toString(), rec['P'].orEmpty(), n.toString()), transferAccount = transfer)
            }
            rec.clear()
        }
        text.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("!") -> Unit
                line == "^" -> flush()
                else -> rec[line[0]] = line.drop(1).trim()
            }
        }
        flush()
        return ImportResult(out, errors)
    }

    /** CAMT.053 (ISO 20022 XML): Ntry with Amt Ccy, CdtDbtInd, BookgDt/Dt, AcctSvcrRef, and remittance info. */
    fun camt053(text: String): ImportResult {
        val out = mutableListOf<ImportedRow>()
        val errors = mutableListOf<String>()
        Regex("<Ntry>(.*?)</Ntry>", RegexOption.DOT_MATCHES_ALL).findAll(text).forEachIndexed { i, m ->
            val b = m.groupValues[1]
            val amt = Regex("<Amt Ccy=\"([A-Z]{3})\">([0-9.]+)</Amt>").find(b)
            val sign = if (Regex("<CdtDbtInd>DBIT</CdtDbtInd>").containsMatchIn(b)) -1 else 1
            val date = Regex("<BookgDt>\\s*<Dt>([0-9-]{10})</Dt>").find(b)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (amt == null || date == null) { errors += "Entry ${i + 1}"; return@forEachIndexed }
            val cur = amt.groupValues[1]
            val minor = BigDecimal(amt.groupValues[2]).setScale(CurrencyUnit.of(cur).minorUnits, RoundingMode.HALF_EVEN).unscaledValue().toLong() * sign
            val name = Regex("<Nm>([^<]*)</Nm>").find(b)?.groupValues?.get(1).orEmpty()
            val info = Regex("<Ustrd>([^<]*)</Ustrd>").find(b)?.groupValues?.get(1).orEmpty()
            val ref = Regex("<AcctSvcrRef>([^<]*)</AcctSvcrRef>").find(b)?.groupValues?.get(1) ?: hash(date.toString(), minor.toString(), name, i.toString())
            out += ImportedRow(date, minor, cur, name, info, externalId = "camt:$ref")
        }
        return ImportResult(out, errors)
    }

    /** MT940: :61: statement lines (YYMMDD, C/D, amount with comma decimals) followed by :86: details. */
    fun mt940(text: String, defaultCurrency: String): ImportResult {
        val cur = Regex(":60F:[CD]\\d{6}([A-Z]{3})").find(text)?.groupValues?.get(1) ?: defaultCurrency
        val out = mutableListOf<ImportedRow>()
        val errors = mutableListOf<String>()
        val lines = text.lines()
        lines.forEachIndexed { i, l ->
            if (!l.startsWith(":61:")) return@forEachIndexed
            val m = Regex(":61:(\\d{6})(\\d{4})?(R?[CD])([A-Z])?([0-9,]+)").find(l)
            if (m == null) { errors += "Line ${i + 1}"; return@forEachIndexed }
            val date = runCatching { LocalDate.parse("20" + m.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return@forEachIndexed
            val sign = if (m.groupValues[3].endsWith("D")) -1 else 1
            val v = BigDecimal(m.groupValues[5].replace(',', '.')).setScale(CurrencyUnit.of(cur).minorUnits, RoundingMode.HALF_EVEN).unscaledValue().toLong() * sign
            val details = lines.getOrNull(i + 1)?.takeIf { it.startsWith(":86:") }?.removePrefix(":86:").orEmpty()
            out += ImportedRow(date, v, cur, details.take(60), details, externalId = "mt940:" + hash(date.toString(), v.toString(), details, i.toString()))
        }
        return ImportResult(out, errors)
    }

    /**
     * Migration presets (CP-09, Appendix C). Header names are matched case-insensitively; the first matching preset
     * builds a [CsvMapping]. Unknown files fall back to the manual column mapper.
     */
    data class Preset(val app: String, val date: List<String>, val amount: List<String>, val outflow: List<String> = emptyList(), val inflow: List<String> = emptyList(), val payee: List<String>, val note: List<String>, val category: List<String>, val account: List<String> = emptyList(), val tags: List<String> = emptyList(), val currency: List<String> = emptyList(), val datePattern: String = "yyyy-MM-dd", val invert: Boolean = false)

    val PRESETS = listOf(
        Preset("YNAB", listOf("date"), emptyList(), listOf("outflow"), listOf("inflow"), listOf("payee"), listOf("memo"), listOf("category", "category group/category"), listOf("account"), datePattern = "MM/dd/yyyy"),
        Preset("Monarch", listOf("date"), listOf("amount"), payee = listOf("merchant"), note = listOf("notes"), category = listOf("category"), account = listOf("account"), tags = listOf("tags")),
        Preset("Mint", listOf("date"), listOf("amount"), payee = listOf("description"), note = listOf("notes", "original description"), category = listOf("category"), account = listOf("account name"), tags = listOf("labels"), datePattern = "M/d/yyyy"),
        Preset("Money Manager", listOf("period", "date"), listOf("amount", "inr", "usd"), payee = listOf("note"), note = listOf("description"), category = listOf("category"), account = listOf("accounts", "account"), datePattern = "dd/MM/yyyy"),
        Preset("Spendee", listOf("date"), listOf("amount"), payee = listOf("note"), note = listOf("note"), category = listOf("category name"), account = listOf("wallet"), tags = listOf("labels"), currency = listOf("currency")),
        Preset("Wallet", listOf("date"), listOf("amount"), payee = listOf("payee"), note = listOf("note"), category = listOf("category"), account = listOf("account"), tags = listOf("labels"), currency = listOf("currency")),
        Preset("Splitwise", listOf("date"), listOf("cost"), payee = listOf("description"), note = listOf("description"), category = listOf("category"), currency = listOf("currency"), invert = true),
        Preset("Bluecoins", listOf("date"), listOf("amount"), payee = listOf("title", "item or payee"), note = listOf("notes"), category = listOf("category"), account = listOf("account"), tags = listOf("labels"), currency = listOf("currency")),
        Preset("Toshl", listOf("date"), listOf("expense amount", "amount"), payee = listOf("description"), note = listOf("description"), category = listOf("category"), account = listOf("account"), tags = listOf("tags"), currency = listOf("currency"), datePattern = "M/d/yy", invert = true),
        Preset("Goodbudget", listOf("date"), listOf("amount"), payee = listOf("name"), note = listOf("notes"), category = listOf("envelope"), account = listOf("account"), datePattern = "MM/dd/yyyy"),
        Preset("EveryDollar", listOf("date"), listOf("amount"), payee = listOf("merchant"), note = listOf("note"), category = listOf("budget item"), account = emptyList(), datePattern = "MM/dd/yyyy"),
        Preset("PocketGuard", listOf("date"), listOf("amount"), payee = listOf("merchant"), note = listOf("note"), category = listOf("category"), account = listOf("account")),
        Preset("Actual", listOf("date"), listOf("amount"), payee = listOf("payee"), note = listOf("notes"), category = listOf("category"), account = listOf("account")),
        Preset("Firefly III", listOf("date"), listOf("amount"), payee = listOf("opposing_name", "description"), note = listOf("notes", "description"), category = listOf("category"), account = listOf("source_name"), tags = listOf("tags"), currency = listOf("currency_code")),
        Preset("Lunch Money", listOf("date"), listOf("amount"), payee = listOf("payee"), note = listOf("notes"), category = listOf("category"), account = listOf("account"), tags = listOf("tags"), currency = listOf("currency"), invert = true),
    )

    fun detectPreset(header: List<String>): Pair<Preset, CsvMapping>? {
        val h = header.map { it.trim().lowercase() }
        fun find(names: List<String>) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } }
        for (p in PRESETS) {
            val date = find(p.date) ?: continue
            val amount = find(p.amount)
            val out = find(p.outflow)
            val inn = find(p.inflow)
            if (amount == null && (out == null || inn == null)) continue
            val payee = find(p.payee)
            val cat = find(p.category)
            if (payee == null && cat == null) continue
            // Require at least one distinctive column beyond date/amount to avoid false positives.
            val score = listOfNotNull(payee, cat, find(p.account), find(p.tags), find(p.currency), out).size
            if (score < 2) continue
            return p to CsvMapping(
                name = p.app, dateCol = date, amountCol = amount, debitCol = out, creditCol = inn, payeeCol = payee,
                noteCol = find(p.note)?.takeIf { it != payee }, categoryCol = cat, accountCol = find(p.account), tagsCol = find(p.tags),
                currencyCol = find(p.currency), datePattern = p.datePattern, invertSign = p.invert,
            )
        }
        return null
    }

    /** Generic bank CSV guess: date, amount (or debit/credit), description columns by header words. */
    fun guessMapping(header: List<String>, sample: List<String>): CsvMapping {
        val h = header.map { it.lowercase() }
        fun idx(vararg w: String) = h.indexOfFirst { c -> w.any { c.contains(it) } }.takeIf { it >= 0 }
        val date = idx("date", "datum", "fecha", "data", "tarikh") ?: 0
        val debit = idx("debit", "withdrawal", "out", "soll")
        val credit = idx("credit", "deposit", "in ", "haben")
        val amount = idx("amount", "betrag", "importe", "valor", "montant") ?: if (debit == null) 1 else null
        val desc = idx("description", "narration", "details", "payee", "merchant", "beschreibung", "concepto", "descri")
        val rawDate = sample.getOrNull(date).orEmpty()
        val pattern = when {
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(rawDate.take(10)) -> "yyyy-MM-dd"
            Regex("\\d{2}\\.\\d{2}\\.\\d{4}").matches(rawDate.take(10)) -> "dd.MM.yyyy"
            Regex("\\d{2}/\\d{2}/\\d{4}").matches(rawDate.take(10)) -> "dd/MM/yyyy"
            Regex("\\d{2}-\\d{2}-\\d{4}").matches(rawDate.take(10)) -> "dd-MM-yyyy"
            else -> "yyyy-MM-dd"
        }
        return CsvMapping("Bank CSV", date, amount, debit, credit, desc, datePattern = pattern)
    }
}
