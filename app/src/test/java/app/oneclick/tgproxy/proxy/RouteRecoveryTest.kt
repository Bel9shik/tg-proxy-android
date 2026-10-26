package app.oneclick.tgproxy.proxy

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RouteRecoveryTest {
    @Suppress("UNCHECKED_CAST")
    private fun failedIps(): MutableMap<String, Long> = Bridge::class.java
        .getDeclaredField("directIpFailUntil").apply { isAccessible = true }
        .get(Bridge) as MutableMap<String, Long>

    @Test(timeout = 10_000)
    fun `listener restart retains failed route until network actually changes`() = runBlocking {
        Bridge.resetState()
        val expires = System.currentTimeMillis() + 300_000
        failedIps()["149.154.167.220"] = expires
        val listening = CompletableDeferred<Unit>()
        val server = TgWsProxyServer(ProxyConfig(port = 0, poolSize = 0, cfProxyEnabled = false))
        server.onStatusChange = { if (it) listening.complete(Unit) }
        val job = launch(Dispatchers.IO) { server.start() }
        try {
            withTimeout(2_000) { listening.await() }
            assertEquals("Wake-up forgot the known broken route", expires, failedIps()["149.154.167.220"])
            server.onNetworkChanged(clearRouteHealth = false)
            assertEquals("A brief loss on the same network must preserve backoff", expires, failedIps()["149.154.167.220"])
            server.onNetworkChanged()
            assertTrue("New network must allow fresh probes", failedIps().isEmpty())
        } finally {
            withContext(Dispatchers.IO) { server.stop() }
            job.cancelAndJoin()
            Bridge.resetState()
        }
    }

    @Test
    fun `late failure from the previous network cannot block the new network`() {
        val oldNetwork = failedIps()
        Bridge.resetState()
        oldNetwork["149.154.167.220"] = System.currentTimeMillis() + 300_000
        assertTrue(failedIps().isEmpty())
    }
}
