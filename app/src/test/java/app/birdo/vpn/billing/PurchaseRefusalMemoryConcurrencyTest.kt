package app.birdo.vpn.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A-M74 (audit finding 15): [PurchaseRefusalMemory] kept a plain set and a plain
 * map, written from concurrent `Dispatchers.Default` coroutines (resume, sign-in
 * and restore sweeps). A lost write means a refused token is presented again;
 * a `ConcurrentModificationException` kills the sweep.
 */
class PurchaseRefusalMemoryConcurrencyTest {

    private val threads = 8
    private val perThread = 4_000

    private fun runAll(body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { t ->
            pool.execute {
                try {
                    start.await()
                    body(t)
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        failure.get()?.let { throw AssertionError("a concurrent caller failed", it) }
    }

    @Test
    fun `concurrent record loses no terminal and no cooldown entry`() {
        val memory = PurchaseRefusalMemory { 0L }

        runAll { t ->
            repeat(perThread) { i ->
                val terminal = i % 2 == 0
                memory.record(
                    "t$t-$i",
                    if (terminal) StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT else StoreLinkRefusal.TRANSIENT,
                )
            }
        }

        var missing = 0
        repeat(threads) { t -> repeat(perThread) { i -> if (!memory.shouldSkip("t$t-$i")) missing++ } }
        assertEquals("every recorded token must still be suppressed", 0, missing)
    }

    @Test
    fun `record, shouldSkip and clear from many threads never throw`() {
        val memory = PurchaseRefusalMemory { 0L }

        runAll { t ->
            repeat(perThread) { i ->
                val token = "t${t % 3}-${i % 50}"
                when (i % 4) {
                    0 -> memory.record(token, StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT)
                    1 -> memory.record(token, StoreLinkRefusal.TRANSIENT)
                    2 -> memory.shouldSkip(token)
                    else -> if (i % 64 == 3) memory.clear() else memory.shouldSkip(token)
                }
            }
        }
    }
}
