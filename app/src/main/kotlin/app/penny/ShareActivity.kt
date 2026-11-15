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
 * the importer (CSV, PDF statements), and a toast links to it. Nothing is posted without the user's review.
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
            val ok = when {
                type.startsWith("text/plain") -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { capture.fromSharedText(it) } ?: false
                type.startsWith("image/") -> stream(i)?.let { uri ->
                    val text = Ocr.recognize(this@ShareActivity, uri) ?: return@let false
                    val book = store.ready()?.book ?: return@let false
                    val parsed = ReceiptParser.parse(text, book.profile.baseCurrency, book.today()) ?: return@let false
                    capture.captured("share", "image", text, parsed, "share:" + app.penny.core.io.Importers.hash(text))
                    true
                } ?: false
                else -> {
                    // CSV and PDF statements go to the importer, which previews before anything is saved.
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${app.penny.core.Brand.DEEP_LINK_SCHEME}://home?tab=home"), this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish(); return@launch
                }
            }
            Toast.makeText(this@ShareActivity, getString(if (ok) R.string.shared_to_review else R.string.shared_not_understood), Toast.LENGTH_LONG).show()
            if (ok) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${app.penny.core.Brand.DEEP_LINK_SCHEME}://review"), this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
}
