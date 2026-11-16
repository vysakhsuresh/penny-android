package app.penny

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.penny.capture.Ocr
import app.penny.core.capture.ReceiptParser
import app.penny.domain.BookStore
import app.penny.domain.CaptureService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP-05 / UX-09: anything shared to Penny lands in the review queue (text, receipt images via on-device OCR) or in
 * the importer (bank files), and a toast links to it. Nothing is posted without the user's review.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var capture: CaptureService
    @Inject lateinit var store: BookStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i?.action != Intent.ACTION_SEND || store.ready() == null) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        val type = i.type.orEmpty()
        lifecycleScope.launch {
            var unreadable = false
            val ok = when {
                type.startsWith("text/plain") -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { capture.fromSharedText(it) } ?: false
                type.startsWith("image/") -> stream(i)?.let { uri ->
                    // An image Penny cannot open is a different problem from a receipt with no readable total.
                    val readable = runCatching { contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
                    if (!readable) { unreadable = true; return@let false }
                    val text = Ocr.recognize(this@ShareActivity, uri) ?: return@let false
                    val book = store.ready()?.book ?: return@let false
                    val parsed = ReceiptParser.parse(text, book.profile.baseCurrency, book.today()) ?: return@let false
                    capture.captured("share", "image", text, parsed, "share:" + app.penny.core.io.Importers.hash(text))
                    true
                } ?: false
                else -> {
                    // Bank files (CSV, OFX, QIF, CAMT, MT940) go to the importer, which previews before anything is saved.
                    val uri = stream(i)
                    val saved = uri != null && runCatching { SharedImport.put(this@ShareActivity, uri) }.getOrDefault(false)
                    if (!saved) Toast.makeText(this@ShareActivity, R.string.import_read_failed, Toast.LENGTH_LONG).show()
                    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${app.penny.core.Brand.DEEP_LINK_SCHEME}://import"), this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish(); return@launch
                }
            }
            Toast.makeText(this@ShareActivity, getString(if (ok) R.string.shared_to_review else if (unreadable) R.string.shared_unreadable else R.string.shared_not_understood), Toast.LENGTH_LONG).show()
            if (ok) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${app.penny.core.Brand.DEEP_LINK_SCHEME}://review"), this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
}

/** Hand-off for a bank file shared to Penny: copied into app cache (the share grant ends with ShareActivity). */
object SharedImport {
    private const val MAX_BYTES = 20L * 1024 * 1024

    private fun dir(context: android.content.Context) = java.io.File(context.cacheDir, "shared-import").apply { mkdirs() }

    suspend fun put(context: android.content.Context, uri: Uri): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "shared.csv"
        val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readNBytesCompat(MAX_BYTES) } ?: return@withContext false
        dir(context).listFiles()?.forEach { it.delete() }
        java.io.File(dir(context), "file").writeBytes(bytes)
        java.io.File(dir(context), "name").writeText(name)
        true
    }

    /** Returns (name, text) once and deletes it. */
    fun take(context: android.content.Context): Pair<String, String>? {
        val f = java.io.File(dir(context), "file")
        if (!f.exists()) return null
        val name = java.io.File(dir(context), "name").takeIf { it.exists() }?.readText() ?: "shared.csv"
        val text = app.penny.ui.settings.readText(f.readBytes())
        dir(context).listFiles()?.forEach { it.delete() }
        return name to text
    }

    private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw java.io.IOException("too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
