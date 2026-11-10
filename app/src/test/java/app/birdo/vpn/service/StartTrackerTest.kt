package app.birdo.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** A-M75: a stop must not destroy a service while a newer start is pending. */
class StartTrackerTest {

    /** Android's rule: stopSelfResult(id) stops only if id is the newest start request. */
    private class FakePlatform {
        var newestRequested = 0
        var stopped = false
        fun stopSelfResult(id: Int): Boolean {
            if (id != newestRequested) return false
            stopped = true
            return true
        }
    }

    @Test
    fun stopQueuedBeforeALaterStartDoesNotStopTheService() {
        val platform = FakePlatform()
        val tracker = StartTracker(platform::stopSelfResult)

        // START(1) delivered, STOP(2) delivered and queued on the executor.
        platform.newestRequested = 1; tracker.record(1)
        platform.newestRequested = 2; tracker.record(2)
        val queuedStop = tracker.bind { tracker.stopUnlessNewerStart() }

        // START(3) is requested (pending, maybe not yet delivered) before the stop runs.
        platform.newestRequested = 3
        queuedStop()

        assertFalse("service must stay alive for the pending start", platform.stopped)

        // Once START(3) is delivered and its own work ends, the stop is allowed.
        tracker.record(3)
        tracker.bind { tracker.stopUnlessNewerStart() }()
        assertTrue(platform.stopped)
    }

    @Test
    fun stopRunsOnTheExecutorThreadWithItsOwnStartId() {
        val platform = FakePlatform()
        val tracker = StartTracker(platform::stopSelfResult)
        platform.newestRequested = 1; tracker.record(1)
        val queued = tracker.bind { tracker.stopUnlessNewerStart() }
        platform.newestRequested = 2; tracker.record(2)

        val ex = Executors.newSingleThreadExecutor()
        try { ex.submit(queued).get() } finally { ex.shutdown() }

        assertFalse(platform.stopped)
    }

    @Test
    fun plainStopWithNothingNewerStops() {
        val platform = FakePlatform()
        val tracker = StartTracker(platform::stopSelfResult)
        platform.newestRequested = 1; tracker.record(1)
        tracker.bind { assertTrue(tracker.stopUnlessNewerStart()) }()
        assertTrue(platform.stopped)
    }

    @Test
    fun synchronousStopOnTheMainThreadUsesTheLatestStart() {
        val platform = FakePlatform()
        val tracker = StartTracker(platform::stopSelfResult)
        platform.newestRequested = 4; tracker.record(4)
        assertTrue(tracker.stopUnlessNewerStart())
        assertTrue(platform.stopped)
    }
}
