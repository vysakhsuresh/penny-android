package app.penny.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.model.AccountType
import app.penny.core.model.Direction
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.ListRow
import app.penny.ui.theme.Penny

/** Icon by default-category key prefix; user categories fall back to initials. */
fun categoryIcon(key: String?): ImageVector? = when {
    key == null -> null
    key.startsWith("inc") -> Icons.AutoMirrored.Outlined.TrendingUp
    key.startsWith("food") -> Icons.Outlined.LocalGroceryStore
    key == "eat.cafe" -> Icons.Outlined.LocalCafe
    key.startsWith("eat") -> Icons.Outlined.Restaurant
    key.startsWith("home") -> Icons.Outlined.Home
    key.startsWith("util") -> Icons.Outlined.Bolt
    key.startsWith("trans") -> Icons.Outlined.DirectionsBus
    key.startsWith("health") -> Icons.Outlined.LocalHospital
    key.startsWith("fam") -> Icons.Outlined.FamilyRestroom
    key == "shop.cloth" -> Icons.Outlined.Checkroom
    key.startsWith("shop") -> Icons.Outlined.ShoppingBag
    key.startsWith("fun") -> Icons.Outlined.SportsEsports
    key.startsWith("travel") -> Icons.Outlined.Flight
    key == "edu" -> Icons.Outlined.School
    key == "pets" -> Icons.Outlined.Pets
    key.startsWith("gift") -> Icons.Outlined.CardGiftcard
    key.startsWith("fin") -> Icons.Outlined.RequestQuote
    key.startsWith("loan") -> Icons.Outlined.Payments
    key == "save" -> Icons.Outlined.Savings
    key == "xfer" -> Icons.AutoMirrored.Outlined.CompareArrows
    key == "adj" -> Icons.Outlined.Tune
    key == "unrec" -> Icons.Outlined.Wallet
    key == "uncat" -> Icons.Outlined.Category
    else -> null
}

fun accountIcon(t: AccountType): ImageVector = when (t) {
    AccountType.CASH -> Icons.Outlined.Wallet
    AccountType.BANK -> Icons.Outlined.AccountBalance
    AccountType.E_WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountType.CREDIT_CARD, AccountType.PREPAID -> Icons.Outlined.CreditCard
    AccountType.LOAN, AccountType.LIABILITY -> Icons.Outlined.Payments
    AccountType.RECEIVABLE -> Icons.Outlined.RequestQuote
    AccountType.INVESTMENT -> Icons.AutoMirrored.Outlined.TrendingUp
    AccountType.ASSET -> Icons.Outlined.Diamond
    AccountType.OTHER -> Icons.Outlined.MoreHoriz
}

fun accountTypeLabel(t: AccountType): Int = when (t) {
    AccountType.CASH -> R.string.acct_cash
    AccountType.BANK -> R.string.acct_bank
    AccountType.E_WALLET -> R.string.acct_ewallet
    AccountType.CREDIT_CARD -> R.string.acct_card
    AccountType.PREPAID -> R.string.acct_prepaid
    AccountType.LOAN -> R.string.acct_loan
    AccountType.RECEIVABLE -> R.string.acct_receivable
    AccountType.INVESTMENT -> R.string.acct_investment
    AccountType.ASSET -> R.string.acct_asset
    AccountType.LIABILITY -> R.string.acct_liability
    AccountType.OTHER -> R.string.acct_other
}

fun isInflow(t: Transaction) = t.type == TxType.INCOME || t.type == TxType.REFUND || t.type == TxType.OPENING_BALANCE ||
    (t.type == TxType.ADJUSTMENT && t.direction == Direction.IN)

/** One transaction as a list row: payee or category, category and account, signed amount with inflows in accent. */
@Composable
fun TxRow(
    id: String,
    t: Transaction,
    ctx: Ctx,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    showDate: Boolean = false,
    /** On an account's page: a transfer into this account reads "From …", in this account's currency, as money in. */
    forAccount: String? = null,
) {
    val b = ctx.book
    val cat = b.categoryMap[t.categoryId]
    val incoming = t.type == TxType.TRANSFER && forAccount != null && forAccount == t.toAccountId
    val outgoing = t.type == TxType.TRANSFER && forAccount != null && forAccount == t.accountId
    val inflow = isInflow(t) || incoming
    val context = LocalContext.current
    val title = t.payee?.takeIf { it.isNotBlank() }
        ?: if (incoming) context.getString(R.string.transfer_from, b.accountName(t.accountId))
        else if (t.type == TxType.TRANSFER) context.getString(R.string.transfer_to, b.accountName(t.toAccountId))
        else cat?.value?.name ?: context.getString(if (t.splits.isNotEmpty()) R.string.split_tx else R.string.uncategorised)
    val parts = listOfNotNull(
        if (showDate) shortDate(t.date) else null,
        b.categoryName(t.categoryId).takeIf { it.isNotEmpty() && t.payee?.isNotBlank() == true },
        if (t.splits.isNotEmpty()) context.resources.getQuantityString(R.plurals.splits_count, t.splits.size, t.splits.size) else null,
        (if (incoming) b.accountName(t.toAccountId) else b.accountName(t.accountId)).takeIf { it.isNotEmpty() }
            ?.let { n -> t.viaAccountId?.takeIf { !incoming }?.let { v -> context.getString(R.string.account_via, n, b.accountName(v)) } ?: n },
        if (t.status == TxStatus.PENDING) context.getString(R.string.status_pending) else null,
        if (t.date.isAfter(b.today())) context.getString(R.string.scheduled) else null,
    )
    val amount = when {
        incoming -> ctx.fmt.format(t.toAmountMinor ?: t.amountMinor, t.toCurrency ?: t.currency, signed = true)
        outgoing -> ctx.fmt.format(-t.amountMinor, t.currency)
        else -> ctx.fmt.format(if (inflow || t.type == TxType.TRANSFER) t.amountMinor else -t.amountMinor, t.currency, signed = inflow)
    }
    ListRow(
        title = title, subtitle = parts.joinToString(" · "), modifier = modifier, selected = selected,
        leading = {
            Avatar(
                cat?.value?.name ?: title, t.categoryId ?: title,
                icon = if (t.type == TxType.TRANSFER) Icons.AutoMirrored.Outlined.CompareArrows else categoryIcon(cat?.value?.key) ?: if (t.isPrivate) Icons.Outlined.Lock else if (t.attachmentIds.isNotEmpty()) Icons.Outlined.Receipt else null,
            )
        },
        trailingText = amount, trailingColor = if (inflow) Penny.status.positive else Color.Unspecified,
        onClick = onOpen, onLongClick = onLongClick,
    )
}

/** The undo line for a deleted transaction names it, so a deletion nobody meant is noticed. */
fun deletedMessage(context: android.content.Context, ctx: Ctx, t: Transaction): String {
    val name = t.payee?.takeIf { it.isNotBlank() } ?: ctx.book.categoryName(t.categoryId).takeIf { it.isNotBlank() } ?: context.getString(R.string.obj_transaction)
    return context.getString(R.string.deleted_named, name, ctx.fmt.format(t.amountMinor, t.currency))
}

/**
 * TX-13: swipe left deletes with undo; swipe right opens the category sheet. Both need a deliberate drag past half the
 * row: a scroll that drifts sideways, or a quick flick, springs back instead of deleting (found on a phone test).
 */
@Composable
fun SwipeableTx(key: String, onDelete: () -> Unit, onCategory: (() -> Unit)?, content: @Composable () -> Unit) {
    var width by remember { mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SwipeToDismissBoxState>(1) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.Settled) true
            else {
                val offset = runCatching { holder[0]?.requireOffset() ?: 0f }.getOrDefault(0f)
                val far = width > 0f && kotlin.math.abs(offset) >= width * 0.5f
                if (far) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                far
            }
        },
        positionalThreshold = { total -> total * 0.5f },
    )
    holder[0] = state
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> { onDelete(); state.snapTo(SwipeToDismissBoxValue.Settled) }
            SwipeToDismissBoxValue.StartToEnd -> { onCategory?.invoke(); state.snapTo(SwipeToDismissBoxValue.Settled) }
            else -> Unit
        }
    }
    // Swipes have TalkBack equivalents: "Delete" and "Category" appear in the actions menu (accessibility, NN-9).
    val deleteLabel = stringResource(R.string.delete)
    val categoryLabel = stringResource(R.string.field_category)
    SwipeToDismissBox(
        modifier = Modifier.onSizeChanged { width = it.width.toFloat() }.semantics {
            customActions = listOfNotNull(
                CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                onCategory?.let { c -> CustomAccessibilityAction(categoryLabel) { c(); true } },
            )
        },
        state = state,
        enableDismissFromStartToEnd = onCategory != null,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (toStart) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                // The action is named, not just drawn, so a half-swipe shows what letting go would do.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!toStart) Icon(Icons.Outlined.Category, null)
                    Text(stringResource(if (toStart) R.string.delete else R.string.field_category), style = MaterialTheme.typography.labelLarge)
                    if (toStart) Icon(Icons.Outlined.Delete, null)
                }
            }
        },
    ) { Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) { content() } }
}

@Composable
fun WorkIcon() = Icon(Icons.Outlined.Work, null)
