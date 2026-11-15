package app.penny.core.model

import app.penny.core.money.MAX_MINOR

enum class TxError {
    AMOUNT_NOT_POSITIVE,
    AMOUNT_TOO_LARGE,
    ACCOUNT_REQUIRED,
    TRANSFER_TARGET_REQUIRED,
    TRANSFER_SAME_ACCOUNT,
    TRANSFER_TO_AMOUNT_REQUIRED,
    ADJUSTMENT_DIRECTION_REQUIRED,
    SPLITS_DO_NOT_SUM,
    SPLIT_NOT_POSITIVE,
}

/** Domain validation (TX-08, §6 invariants 1 and 2). The amount sign comes from the type, never from the number. */
object TransactionValidator {
    fun validate(t: Transaction, accountCurrency: (String) -> String? = { null }): List<TxError> = buildList {
        if (t.amountMinor <= 0) add(TxError.AMOUNT_NOT_POSITIVE)
        if (t.amountMinor > MAX_MINOR) add(TxError.AMOUNT_TOO_LARGE)
        if (t.accountId.isBlank()) add(TxError.ACCOUNT_REQUIRED)
        if (t.type == TxType.TRANSFER) {
            when {
                t.toAccountId.isNullOrBlank() -> add(TxError.TRANSFER_TARGET_REQUIRED)
                t.toAccountId == t.accountId -> add(TxError.TRANSFER_SAME_ACCOUNT)
            }
            val toCur = t.toCurrency ?: t.toAccountId?.let(accountCurrency)
            if (toCur != null && toCur != t.currency && (t.toAmountMinor == null || t.toAmountMinor <= 0)) {
                add(TxError.TRANSFER_TO_AMOUNT_REQUIRED)
            }
        }
        if (t.type == TxType.ADJUSTMENT && t.direction == null) add(TxError.ADJUSTMENT_DIRECTION_REQUIRED)
        if (t.splits.isNotEmpty()) {
            if (t.splits.any { it.amountMinor <= 0 }) add(TxError.SPLIT_NOT_POSITIVE)
            if (t.splits.sumOf { it.amountMinor } != t.amountMinor) add(TxError.SPLITS_DO_NOT_SUM)
        }
    }
}
