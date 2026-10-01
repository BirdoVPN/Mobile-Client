package app.birdo.vpn.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryAfterTest {

    @Test
    fun `the quota check's 503 says when to come back`() {
        // birdo-web PR #590's body, verbatim in shape.
        val body = """{"statusCode":503,"error":"quota_check_unavailable","message":"Try again shortly",""" +
            """"details":{"retryable":true,"retryAfterSeconds":30}}"""
        assertEquals(30_000L, RetryAfter.fromBody(body))
        assertEquals(15_000L, RetryAfter.fromBody("""{"retryAfterSeconds":15}"""))
    }

    @Test
    fun `a wait is clamped, and anything unreadable is no wait at all`() {
        assertEquals(RetryAfter.MAX_MS, RetryAfter.fromBody("""{"details":{"retryAfterSeconds":86400}}"""))
        assertNull(RetryAfter.fromBody("""{"details":{"retryAfterSeconds":0}}"""))
        assertNull(RetryAfter.fromBody("""{"details":{"retryAfterSeconds":"soon"}}"""))
        assertNull(RetryAfter.fromBody("<html>502</html>"))
        assertNull(RetryAfter.fromBody(null))
        assertEquals(30_000L, RetryAfter.fromHeader(" 30 "))
        // An HTTP date compares the server's clock with the device's: ignored.
        assertNull(RetryAfter.fromHeader("Wed, 01 Oct 2026 12:00:00 GMT"))
        assertNull(RetryAfter.fromHeader(null))
    }
}
