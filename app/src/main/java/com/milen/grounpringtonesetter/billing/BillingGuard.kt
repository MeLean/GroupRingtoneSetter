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

}
