package app.penny.ui.kit

import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.semantics.role
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.budget.EnvelopeState
import app.penny.ui.theme.LocalPenny
import app.penny.ui.theme.Penny
import kotlin.math.absoluteValue

/** Standard screen: large collapsing title, back arrow, actions; content gets the inner padding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PennyScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    large: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()) else TopAppBarDefaults.pinnedScrollBehavior()
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer)
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // While amounts are hidden, every screen offers the way back, not only Home.
            val withReveal: @Composable RowScope.() -> Unit = { RevealAmountsButton(); actions() }
            val nav: @Composable () -> Unit = {
                if (onBack != null) BackPill(onBack)
            }
            if (large) {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = nav, actions = withReveal, scrollBehavior = behavior, colors = colors,
                )
            } else {
                TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = nav, actions = withReveal, scrollBehavior = behavior, colors = colors)
            }
        },
        floatingActionButton = floating,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * The way back on every screen: a soft pill with a chevron and the word "Back", easier to read than a bare arrow.
 * The 48 dp touch target is kept around the smaller visual pill; the chevron mirrors in right-to-left languages.
 */
@Composable
fun BackPill(onBack: () -> Unit) {
    val label = stringResource(R.string.back)
    Surface(
        onClick = onBack, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(start = 12.dp).heightIn(min = 48.dp).padding(vertical = 6.dp)
            .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button },
    ) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(2.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, info: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        if (info != null) InfoButton(title, info)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** An "i" beside something whose meaning is not obvious; it opens a short plain-language explanation. */
@Composable
fun InfoButton(title: String, info: String, tint: Color = MaterialTheme.colorScheme.primary) {
    var explain by remember { mutableStateOf(false) }
    IconButton(onClick = { explain = true }, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Outlined.Info, stringResource(R.string.what_is_x, title), tint = tint, modifier = Modifier.size(18.dp))
    }
    if (explain) androidx.compose.material3.AlertDialog(
        onDismissRequest = { explain = false },
        icon = { Icon(Icons.Outlined.Info, null) },
        title = { Text(title) },
        text = { Text(info, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.got_it)) } },
    )
}

/** Grouped surface for rows and content: soft container, rounded corners, no heavy borders. */
@Composable
fun PennyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    // Light: white cards on the tinted page. Dark: cards sit a step above the page, never below it (they read as holes).
    color: Color = Penny.card,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier.fillMaxWidth().padding(horizontal = Penny.spacing.gutter.dp, vertical = 6.dp)
    val colors = CardDefaults.cardColors(containerColor = color)
    // A hairline edge gives cards a crisp, finished outline on both page colours (no heavy borders).
    val edge = androidx.compose.foundation.BorderStroke(1.dp, if (Penny.extras.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f))
    // The last row's divider would sit on the card edge; trim that 1 dp hairline here instead of at every call site.
    val inner = Modifier.padding(padding).trimBottomHairline().animateContentSize()
    androidx.compose.runtime.CompositionLocalProvider(LocalInCard provides true) {
        if (onClick != null) Card(onClick = onClick, modifier = m, colors = colors, shape = MaterialTheme.shapes.large, border = edge) { Column(inner, content = content) }
        else Card(modifier = m, colors = colors, shape = MaterialTheme.shapes.large, border = edge) { Column(inner, content = content) }
    }
}

/** True inside a [PennyCard]: row dividers there run to the card edge; on the open page they stop short of it. */
val LocalInCard = androidx.compose.runtime.compositionLocalOf { false }

private fun Modifier.trimBottomHairline(): Modifier = this.clipToBounds().layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val trim = 1.dp.roundToPx().coerceAtMost(p.height)
    layout(p.width, p.height - trim) { p.place(0, 0) }
}

/** List row: 56–72 dp, leading visual, two lines, trailing value. Long-press selects (TX-13). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingColor: Color = Color.Unspecified,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    divider: Boolean = false,
    titleMaxLines: Int = 1,
    subtitleColor: Color = Color.Unspecified,
    titleWeight: FontWeight = FontWeight.SemiBold,
) {
    val haptic = LocalHapticFeedback.current
    val haptics = LocalPenny.current.haptics
    val clickMod = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick?.let { lc -> { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            role = Role.Button,
        )
    } else Modifier
    Column(modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) {
        Row(
            clickMod.fillMaxWidth().heightIn(min = if (LocalPenny.current.dense) 52.dp else 60.dp).padding(horizontal = 16.dp, vertical = if (LocalPenny.current.dense) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = titleWeight, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null || trailingText != null) Spacer(Modifier.width(12.dp))
            if (trailingText != null) {
                Column(horizontalAlignment = Alignment.End) {
                    MoneyLabel(trailingText, color = trailingColor, style = MaterialTheme.typography.titleMedium)
                    if (supporting != null) Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // In a Box so a menu opened from a trailing ⋮ anchors to that button, not to the start of the whole row.
            if (trailing != null) Box { trailing() }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = if (leading != null) 70.dp else 16.dp, end = if (LocalInCard.current) 0.dp else 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Initials or icon in a tinted circle; colour is stable for the [key] (IN-02). */
@Composable
fun Avatar(label: String, key: String, modifier: Modifier = Modifier, icon: ImageVector? = null, size: Dp = 40.dp, color: Color? = null) {
    val palette = LocalPenny.current.entityPalette
    val c = color ?: palette[(key.hashCode().absoluteValue) % palette.size]
    Box(
        modifier.size(size).clip(CircleShape).background(c.copy(alpha = if (LocalPenny.current.isDark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = c, modifier = Modifier.size(size * 0.5f))
        else Text(initials(label), style = MaterialTheme.typography.labelLarge, color = c, fontWeight = FontWeight.Bold)
    }
}

fun initials(s: String): String = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "•" }

/** Money text with tabular figures; hidden behind dots when "hide amounts" is on (SE-04). */
@Composable
fun MoneyLabel(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, align: TextAlign? = null) {
    val hidden = LocalPenny.current.hideAmounts
    val hiddenLabel = stringResource(R.string.amount_hidden)
    // Never clip digits (₹1,23,45,678 must not read as ₹1,23,45): shrink step by step until the amount fits, down to
    // half size, and only then draw. Matters at 200% text and in narrow columns.
    val shown = if (hidden) "••••" else text
    var scale by remember(shown, style) { mutableStateOf(1f) }
    var fitted by remember(shown, style) { mutableStateOf(false) }
    val base = style.copy(fontFeatureSettings = app.penny.ui.theme.TabularNumbers)
    val sized = if (base.fontSize.isSp && scale < 1f) base.copy(fontSize = base.fontSize * scale, lineHeight = if (base.lineHeight.isSp) base.lineHeight * scale else base.lineHeight) else base
    Text(
        shown,
        modifier = (if (hidden) modifier.semantics { contentDescription = hiddenLabel } else modifier).drawWithContent { if (fitted) drawContent() },
        style = sized,
        color = color, maxLines = 1, softWrap = false, textAlign = align,
        onTextLayout = { r -> if (r.hasVisualOverflow && scale > 0.5f && base.fontSize.isSp) scale *= 0.9f else fitted = true },
    )
}

/** Budget meter: accent while fine, amber when near, orange when over. Never red (UX-06, AL-08). */
@Composable
fun Meter(usedBp: Int, state: EnvelopeState, modifier: Modifier = Modifier, height: Dp = 8.dp, paceBp: Int? = null) {
    val s = Penny.status
    val fill = budgetStateColor(state)
    val reduce = LocalPenny.current.reduceMotion
    val target = (usedBp / 10_000f).coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, animationSpec = tween(if (reduce) 0 else 600), label = "meter")
    val desc = stringResource(R.string.meter_desc, usedBp / 100)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(s.track).semantics { stateDescription = desc }) {
        Box(Modifier.fillMaxWidth(anim).height(height).clip(RoundedCornerShape(height)).background(fill))
        if (paceBp != null) {
            Box(Modifier.fillMaxWidth((paceBp / 10_000f).coerceIn(0f, 1f)).height(height), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).height(height).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer, icon: ImageVector? = null) {
    Row(
        // Large text wraps inside the pill (a 16 dp radius keeps two lines looking like a pill) instead of being cut off.
        modifier.clip(RoundedCornerShape(16.dp)).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = content, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** UX-01 designed states, each with one next action. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Inbox, action: String? = null, onAction: (() -> Unit)? = null, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        // A comfortable measure (~40 characters) so the explanation wraps into an even block, not a long ragged line.
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
        if (secondary != null && onSecondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading)
    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

@Composable
fun ErrorState(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    EmptyState(
        stringResource(R.string.error_title), stringResource(R.string.error_generic), modifier, Icons.Outlined.WarningAmber,
        action = onRetry?.let { stringResource(R.string.retry) }, onAction = onRetry,
    )
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Pill(stringResource(R.string.offline_banner), modifier.padding(horizontal = 16.dp), icon = Icons.Outlined.CloudOff)
}

/** Big primary action at the bottom of forms. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, emphasis: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MoneyLabel(value, color = valueColor, style = if (emphasis) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
    }
}

/** One figure in a [StatsRow]. */
data class Stat(val label: String, val value: String, val color: Color = Color.Unspecified)

/**
 * Two or three figures side by side; at large text (over 130%) they become label-and-value rows, so amounts keep one
 * size and never shrink unevenly to fit narrow columns.
 */
@Composable
fun StatsRow(stats: List<Stat>, modifier: Modifier = Modifier) {
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    MoneyLabel(s.value, color = s.color, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    } else Row(modifier.fillMaxWidth().padding(16.dp)) {
        stats.forEach { s -> StatBlock(s.label, s.value, Modifier.weight(1f), s.color) }
    }
}

/** Constrains content width on tablets and landscape (UX-08) while phones use the full width. */
@Composable
fun Readable(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), content = content)
    }
}

fun Modifier.gutter() = this.padding(horizontal = 16.dp)

@Composable
fun budgetStateColor(state: EnvelopeState): Color {
    val s = Penny.status
    return when (state) {
        EnvelopeState.OVER -> s.over
        EnvelopeState.NEAR, EnvelopeState.AT -> s.near
        else -> MaterialTheme.colorScheme.primary
    }
}


/**
 * The screen's main "add" button. The label is set as the button's own name: the extended FAB in this Material
 * version did not expose its text, so TalkBack announced only "Button" (found on a phone test).
 */
@Composable
fun AddFab(label: String, onClick: () -> Unit) {
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(androidx.compose.material.icons.Icons.Filled.Add, null) },
        text = { Text(label) },
        modifier = Modifier.semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button },
    )
}


/**
 * Secondary actions on a detail screen (pause, archive, delete...): pill buttons that wrap neatly onto a second line
 * instead of a row of loose text links that ran off the edge or broke unevenly.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ActionStrip(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun StripAction(onClick: () -> Unit, destructive: Boolean = false, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = c),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}


/** A compact destructive action for list rows and dialogs (Delete, Remove): text in the muted error colour. */
@Composable
fun DangerTextButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}

/** Flips "hide amounts"; provided by the activity so any screen can offer it. */
val LocalHideToggle = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Shown only while amounts are hidden: one tap shows them again, from whatever screen the person is on. */
@Composable
fun RevealAmountsButton() {
    val toggle = LocalHideToggle.current ?: return
    if (!LocalPenny.current.hideAmounts) return
    IconButton(onClick = toggle) { Icon(androidx.compose.material.icons.Icons.Outlined.VisibilityOff, stringResource(R.string.show_amounts)) }
}

/**
 * The one rich surface per screen (safe to spend, net worth, balances): a deep plum gradient with a soft light sheen
 * and a faint coin-ring motif, like a premium card. Inside, the theme is re-pointed so existing content reads light on
 * plum with gold accents, without each screen choosing colours.
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalPenny.current.isDark
    // Follows the accent chosen in Appearance (plum by default).
    val (top, mid, end) = LocalPenny.current.hero
    val ink = Color(0xFFF6F0FF)
    val gold = Color(0xFFF2C14E)
    val cs = MaterialTheme.colorScheme.copy(
        primaryContainer = Color.Transparent, onPrimaryContainer = ink, primary = gold, onPrimary = Color(0xFF2B1D00),
        onSurface = ink, onSurfaceVariant = ink.copy(alpha = 0.78f), surfaceContainerLowest = Color.White.copy(alpha = 0.10f),
    )
    val extras = LocalPenny.current.let { e -> e.copy(status = e.status.copy(track = Color.White.copy(alpha = 0.18f), positive = Color(0xFF7BE8B6))) }
    val shape = MaterialTheme.shapes.extraLarge
    val base = modifier.fillMaxWidth().padding(horizontal = Penny.spacing.gutter.dp, vertical = 8.dp)
        .shadow(if (dark) 0.dp else 10.dp, shape, ambientColor = top, spotColor = top)
        .clip(shape)
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(top, mid, end)))
        .drawBehind {
            // Light sheen from the top left, and a faint gold coin ring bleeding off the top right corner.
            // drawRect, not drawCircle: the gradient carries its own centre and radius, and drawCircle's defaults put a
            // hard-edged disc in the middle of the card (it showed through as a blob on short cards).
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.15f, 0f), radius = size.width * 0.75f))
            val r = size.minDimension * 0.55f
            val c = Offset(size.width * 0.98f, size.height * 0.02f)
            drawCircle(gold.copy(alpha = 0.10f), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.16f))
            drawCircle(gold.copy(alpha = 0.07f), radius = r * 0.32f, center = c)
        }
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    androidx.compose.runtime.CompositionLocalProvider(LocalPenny provides extras, androidx.compose.material3.LocalContentColor provides ink) {
        MaterialTheme(colorScheme = cs, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            Column(base, content = content)
        }
    }
}

/** A book's badge: deep plum disc, gold initial and a thin gold ring, matching the app icon. */
@Composable
fun ProfileBadge(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val gold = Color(0xFFF2C14E)
    Box(
        modifier.size(size).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(LocalPenny.current.hero.let { listOf(it[0], it[2]) }))
            .border(1.5.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFFFE7A3), gold, Color(0xFFB8801C))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "•" },
            style = MaterialTheme.typography.titleMedium.copy(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFF0B8), gold, Color(0xFFD9A030))),
                fontSize = (size.value * 0.45f).sp,
            ),
            fontWeight = FontWeight.Bold,
        )
    }
}
