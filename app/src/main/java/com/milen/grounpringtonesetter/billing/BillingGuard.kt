import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.atomic.AtomicLong

object BillingGuard {
    private const val LAUNCH_WINDOW_MS = 10000L
    private val lastLaunchTime = AtomicLong(0L)

    fun beginLaunch() {
        lastLaunchTime.set(System.currentTimeMillis())
    }

    fun endLaunch() {
        lastLaunchTime.set(0L)
    }

    fun isExpecting(): Boolean {
        val t = lastLaunchTime.get()
        if (t == 0L) return false
        return (System.currentTimeMillis() - t) < LAUNCH_WINDOW_MS
    }

    fun hasValidBillingExtras(intent: Intent?): Boolean {
        val e = intent?.extras ?: return false
        if (e.isEmpty) return false
        // Require a non-null PendingIntent for any of the known keys.
        // Do NOT accept "result_receiver" alone – it doesn't start a purchase flow.
        return hasPI(e, "BUY_INTENT") || hasPI(e, "SUBS_MANAGEMENT_INTENT") || hasPI(
            e,
            "IN_APP_MESSAGE_INTENT"
        )
    }

    private fun hasPI(b: Bundle, key: String): Boolean {
        return try {
            val pi =
                if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, PendingIntent::class.java)
                else @Suppress("DEPRECATION") b.getParcelable<PendingIntent>(key)
            // Extra safety: ensure the intentSender exists
            pi?.intentSender != null
        } catch (_: Throwable) {
            false
        }
    }
}