package app.oneclick.tgproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class CfCooldownTrackerTest {
    @Test
    fun appliesAndExpiresRateLimitCooldown() {
        var now = 1_000L
        val tracker = CfCooldownTracker { now }

        assertEquals(45_000L, tracker.markRateLimited("example.com"))
        assertTrue(tracker.isCoolingDown("example.com"))

        now += 45_000L
        assertFalse(tracker.isCoolingDown("example.com"))

        assertEquals(90_000L, tracker.markRateLimited("example.com"))
        tracker.clear("example.com")
        assertFalse(tracker.isCoolingDown("example.com"))
    }

    @Test
    fun backsOffRepeatedRateLimitsUpToFiveMinutes() {
        assertEquals(45_000L, CfCooldownTracker.cooldownDelayMs(1))
        assertEquals(90_000L, CfCooldownTracker.cooldownDelayMs(2))
        assertEquals(180_000L, CfCooldownTracker.cooldownDelayMs(3))
        assertEquals(300_000L, CfCooldownTracker.cooldownDelayMs(4))
        assertEquals(300_000L, CfCooldownTracker.cooldownDelayMs(20))
    }

    @Test
    fun coolsDownHttp503RelayButNotWholeNetworkTimeout() {
        assertTrue(
            shouldCooldownCfDomain(
                WsHandshakeError(503, "HTTP/1.1 503 Service Unavailable", null)
            )
        )
        assertFalse(shouldCooldownCfDomain(SocketTimeoutException("network unavailable")))
    }
}
