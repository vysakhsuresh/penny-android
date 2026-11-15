package app.penny.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.penny.core.capture.NotificationParser
import app.penny.core.capture.ParsedCapture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Where captured items go. The app implements it (review queue, rules, dedupe, auto-post per source). */
interface CaptureSink {
    /** Region pack templates and allowed packages for the open profile; empty when capture is off. */
    fun config(): CaptureConfig
    suspend fun captured(source: String, detail: String, raw: String, parsed: ParsedCapture, externalId: String)
}

data class CaptureConfig(
    val enabled: Boolean,
    val packages: Set<String>,
    val templates: List<app.penny.core.capture.NotificationTemplate>,
    val currency: String,
)

/** The app sets this at start; the listener runs in the app process. */
object CaptureRegistry {
    @Volatile var sink: CaptureSink? = null
}

/**
 * CP-02: reads notifications from the payment and bank apps the regional pack lists, on device only. Never requests
 * READ_SMS. Opt-in through system settings after a plain explanation; off switch in Settings → Capture.
 */
class PennyNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sink = CaptureRegistry.sink ?: return
        val cfg = sink.config()
        if (!cfg.enabled || sbn.packageName !in cfg.packages) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (text.isBlank()) return
        val parsed = NotificationParser.parse(title, text, sbn.packageName, cfg.templates, cfg.currency) ?: return
        // A stable key so the same notification re-posted (updates, group summaries) dedupes (CP-11).
        val key = "notif:${sbn.packageName}:${sbn.postTime / 60_000}:${parsed.amountMinor}"
        scope.launch { sink.captured("notification", sbn.packageName, "$title\n$text", parsed, key) }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, PennyNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun settingsIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

/** CP-07: on-device OCR. Returns the recognised text with line breaks, or null when nothing was found. */
object Ocr {
    suspend fun recognize(context: Context, uri: Uri): String? = suspendCancellableCoroutine { cont ->
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
        if (image == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { r -> cont.resume(r.textBlocks.joinToString("\n") { b -> b.lines.joinToString("\n") { it.text } }.ifBlank { null }); recognizer.close() }
            .addOnFailureListener { cont.resume(null); recognizer.close() }
    }

    suspend fun recognize(bitmap: android.graphics.Bitmap): String? = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { r -> cont.resume(r.text.ifBlank { null }); recognizer.close() }
            .addOnFailureListener { cont.resume(null); recognizer.close() }
    }
}
