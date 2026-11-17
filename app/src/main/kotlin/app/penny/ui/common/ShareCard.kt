package app.penny.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.penny.R
import app.penny.core.Brand
import java.io.File

/** One line on a shared card: a label on the left and its value on the right. */
data class ShareRow(val label: String, val value: String, val tone: Tone = Tone.Plain) {
    enum class Tone { Plain, Good, Warn }
}

/**
 * Shares a designed image card (with the same text as a caption) instead of bare text: the app's plum card with a
 * gold coin-ring, a headline figure and labelled rows. Drawn on the phone; nothing is uploaded.
 */
object ShareCard {
    private const val W = 1080
    private const val PAD = 72f

    fun share(context: Context, title: String, subtitle: String?, heroLabel: String?, hero: String?, rows: List<ShareRow>, caption: String) {
        val uri = runCatching {
            val bmp = render(context, title, subtitle, heroLabel, hero, rows)
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "penny-card.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, context.packageName + ".files", f)
        }.getOrNull()
        val send = if (uri != null) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, caption).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = ClipData.newRawUri(null, uri) }
        else Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, caption)
        context.safeStart(Intent.createChooser(send, null))
    }

    fun render(context: Context, title: String, subtitle: String?, heroLabel: String?, hero: String?, rows: List<ShareRow>): Bitmap {
        val font = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull() ?: Typeface.DEFAULT
        fun paint(size: Float, weight: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(font, weight, false) else Typeface.create(font, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        val white = 0xFFF6F0FF.toInt()
        val soft = 0xC8F6F0FF.toInt()
        val gold = 0xFFF2C14E.toInt()
        val inner = (W - 2 * PAD).toInt()
        fun layout(t: String, p: TextPaint) = StaticLayout.Builder.obtain(plainShareText(t), 0, plainShareText(t).length, p, inner).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val brand = paint(34f, 800, gold)
        val titleL = layout(title, paint(56f, 700, white))
        val subL = subtitle?.let { layout(it, paint(34f, 500, soft)) }
        val heroLabelL = heroLabel?.let { layout(it, paint(32f, 600, soft)) }
        val heroP = paint(104f, 800, white).apply { while (measureText(plainShareText(hero.orEmpty())) > inner && textSize > 48f) textSize -= 4f }
        val rowLabel = paint(36f, 500, soft)
        val rowH = 84f

        var h = PAD + 50f + 40f + titleL.height + (subL?.height?.plus(12) ?: 0) + 48f
        if (hero != null) h += (heroLabelL?.height ?: 0) + heroP.textSize * 1.15f + 40f
        h += rows.size * rowH + 40f + 60f + PAD
        val bmp = Bitmap.createBitmap(W, h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Card: deep plum gradient, a light sheen from the top left and a faint gold coin ring off the top right.
        val card = RectF(0f, 0f, W.toFloat(), h)
        c.drawRoundRect(card, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), h, intArrayOf(0xFF6A34B0.toInt(), 0xFF4A2186.toInt(), 0xFF2E1259.toInt()), null, Shader.TileMode.CLAMP)
        })
        c.drawCircle(W * 0.15f, 0f, W * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(W * 0.15f, 0f, W * 0.8f, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        val r = W * 0.36f
        c.drawCircle(W * 0.98f, 0f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.16f; color = 0x1FF2C14E })
        c.drawCircle(W * 0.98f, 0f, r * 0.32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14F2C14E })

        var y = PAD + 34f
        c.drawText(Brand.NAME, PAD, y, brand)
        y += 40f + 30f
        fun draw(l: StaticLayout) { c.save(); c.translate(PAD, y); l.draw(c); c.restore(); y += l.height }
        draw(titleL)
        subL?.let { y += 12f; draw(it) }
        y += 48f
        if (hero != null) {
            heroLabelL?.let { draw(it) }
            y += heroP.textSize
            c.drawText(plainShareText(hero), PAD, y, heroP)
            y += heroP.textSize * 0.15f + 40f
        }
        val line = Paint().apply { color = 0x26FFFFFF; strokeWidth = 2f }
        rows.forEach { row ->
            c.drawLine(PAD, y, W - PAD, y, line)
            val base = y + rowH / 2 + 13f
            val valueP = paint(38f, 700, when (row.tone) { ShareRow.Tone.Good -> 0xFF7BE8B6.toInt(); ShareRow.Tone.Warn -> 0xFFFFC46B.toInt(); else -> white })
            val v = plainShareText(row.value)
            val vw = valueP.measureText(v)
            val label = android.text.TextUtils.ellipsize(plainShareText(row.label), rowLabel, inner - vw - 32f, android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(label, PAD, base, rowLabel)
            c.drawText(v, W - PAD - vw, base, valueP)
            y += rowH
        }
        y += 40f
        c.drawText(context.getString(R.string.share_card_footer, Brand.NAME), PAD, y + 24f, paint(28f, 600, 0xB3F2C14E.toInt()))
        return bmp
    }
}
