package app.oneclick.tgproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.*
import org.junit.Test

class RawWebSocketLifecycleTest {
    private fun webSocket(socket: SSLSocket, input: InputStream, output: OutputStream): RawWebSocket {
        val constructor = RawWebSocket::class.java.getDeclaredConstructor(
            SSLSocket::class.java, InputStream::class.java, OutputStream::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(socket, input, output)
    }

    @Test
    fun `peer EOF must release the underlying socket`() {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        try {
            val ws = webSocket(socket, ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
            assertNull(ws.readBinary())
            ws.close()
            assertTrue("EOF followed by close leaked the TLS socket", socket.isClosed)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `peer close frame must release the underlying socket`() {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        try {
            val ws = webSocket(socket, ByteArrayInputStream(byteArrayOf(0x88.toByte(), 0)), ByteArrayOutputStream())
            assertNull(ws.readBinary())
            ws.close()
            assertTrue("Close frame leaked the TLS socket", socket.isClosed)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `shutdown does not wait for a blocked writer`() {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val output = ByteArrayOutputStream()
        val ws = webSocket(socket, ByteArrayInputStream(byteArrayOf()), output)
        val finished = CountDownLatch(1)
        var closer: Thread? = null
        try {
            synchronized(output) {
                closer = Thread {
                    try { ws.close() } finally { finished.countDown() }
                }.apply { isDaemon = true; start() }
                assertTrue("Shutdown waited for the writer lock", finished.await(1, TimeUnit.SECONDS))
                assertTrue(socket.isClosed)
            }
        } finally {
            socket.close()
            closer?.join(2_000)
        }
    }
}
