package app.oneclick.tgproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CfConnectorTest {
    private fun webSocket(socket: SSLSocket): RawWebSocket = RawWebSocket::class.java
        .getDeclaredConstructor(SSLSocket::class.java, InputStream::class.java, OutputStream::class.java)
        .apply { isAccessible = true }
        .newInstance(socket, ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())

    @Test(timeout = 5_000)
    fun `ready fallback is delivered without waiting for another domain`() = runBlocking {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val ready = webSocket(socket)
        val connected = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val result = async(Dispatchers.IO) {
            connectCfDomains(listOf("failed", "ready", "slow")) { domain ->
                when (domain) {
                    "ready" -> ready.also { connected.countDown() }
                    "slow" -> { releaseSlow.await(3, TimeUnit.SECONDS); null }
                    else -> null
                }
            }
        }
        try {
            assertTrue(connected.await(1, TimeUnit.SECONDS))
            assertNotNull("Ready route was held up by the other connection",
                withTimeoutOrNull(300) { result.await() })
            assertSame(ready, result.await()!!.second)
        } finally {
            releaseSlow.countDown()
            result.cancelAndJoin()
            ready.close()
        }
    }

    @Test
    fun `no available domain returns null without leaking a result`() = runBlocking {
        val attempted = mutableListOf<String>()
        assertNull(connectCfDomains(listOf("one", "two")) { attempted += it; null })
        assertEquals(listOf("one", "two"), attempted)
        assertNull(connectCfDomains(emptyList()) { fail("No domain to connect"); null })
    }

    @Test
    fun `cancelled dial releases a socket that finished connecting late`() = runBlocking {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val ws = webSocket(socket)
        val owner = Job()
        try {
            try {
                withContext(owner) {
                    connectCfDomains(listOf("ready")) {
                        owner.cancel()
                        ws
                    }
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue("Cancelled connection leaked its socket", socket.isClosed)
            }
        } finally {
            owner.cancel()
            ws.close()
        }
    }
}
