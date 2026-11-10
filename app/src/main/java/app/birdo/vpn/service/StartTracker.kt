package app.birdo.vpn.service

/**
 * Decides when the service may stop itself without killing a start that is
 * still on its way (A-M75).
 *
 * `Service.stopSelf()` stops the service whatever is pending. When a
 * `startForegroundService()` is still queued (rapid STOP then START on a
 * server switch), the platform destroys the service, the pending start never
 * reaches onStartCommand, and the app dies with
 * ForegroundServiceDidNotStartInTimeException. `stopSelfResult(id)` stops only
 * when [id] belongs to the most recent start request, pending ones included.
 *
 * Every stop is therefore tied to the newest start the service had seen when
 * the work that decided to stop it was queued: a stop that runs later, on the
 * lifecycle executor, after another start arrived, is refused.
 */
internal class StartTracker {

    @Volatile private var latest = 0
    private val taskStartId = ThreadLocal<Int?>()

    /** Call first thing in onStartCommand, on the main thread. */
    fun record(startId: Int) {
        latest = startId
    }

    /** Wrap [task] so a stop inside it is bound to the newest start seen NOW. */
    fun bind(task: () -> Unit): () -> Unit {
        val id = latest
        return {
            taskStartId.set(id)
            try {
                task()
            } finally {
                taskStartId.remove()
            }
        }
    }

    /** The id to hand to `stopSelfResult`: the start the running task was queued under. */
    fun stopId(): Int = taskStartId.get() ?: latest
}
