package app.oneclick.tgproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BridgeSessionTest {
    private class FeedInputStream : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        fun feed(data: ByteArray) { data.forEach { bytes.put(it.toInt() and 255) } }
        override fun read(): Int = bytes.take()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            buffer[offset] = first.toByte()
            var count = 1
            while (count < length) {
                val next = bytes.poll() ?: break
                if (next < 0) { bytes.put(-1); break }
                buffer[offset + count++] = next.toByte()
            }
            return count
        }
        override fun close() { bytes.offer(-1) }
    }

    @Test(timeout = 30_000)
    fun `verified CF session stays open after one way traffic`() = runBlocking {
        val clientInput = FeedInputStream()
        val remoteInput = FeedInputStream()
        val closed = CountDownLatch(1)
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val constructor = RawWebSocket::class.java.getDeclaredConstructor(
            SSLSocket::class.java, InputStream::class.java, OutputStream::class.java,
        ).apply { isAccessible = true }
        val sent = CountDownLatch(2) // Relay init and the batch of two transport packets.
        val ws = constructor.newInstance(socket, remoteInput, object : ByteArrayOutputStream() {
            override fun flush() { sent.countDown() }
        })
        val verified = CountDownLatch(1)
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_INTERMEDIATE, 2)
        val key = ByteArray(32)
        val iv = ByteArray(16)
        val job = launch(Dispatchers.IO) {
            Bridge.bridgeReencrypt(
                clientInput, ByteArrayOutputStream(),
                Bridge.UpstreamConnection(Bridge.UpstreamType.CFPROXY, ws = ws),
                CryptoCtx(AesCtr(key, iv), AesCtr(key, iv), relay.telegramDecryptor, relay.telegramEncryptor),
                relay.initPacket, MtProtoConstants.TAG_INTERMEDIATE, 2, false, { verified.countDown() }, { closed.countDown(); clientInput.close(); remoteInput.close() },
            )
        }
        try {
            // Any encrypted downstream data verifies the transport; contents remain opaque.
            remoteInput.feed(byteArrayOf(0x82.toByte(), 4, 1, 2, 3, 4))
            assertTrue(verified.await(3, TimeUnit.SECONDS))
            val packets = byteArrayOf(4, 0, 0, 0, 1, 2, 3, 4, 4, 0, 0, 0, 5, 6, 7, 8)
            clientInput.feed(AesCtr(key, iv).update(packets))
            assertTrue(sent.await(3, TimeUnit.SECONDS))
            delay(22_000)
            assertEquals("Healthy opaque stream was closed by a response heuristic", 1L, closed.count)
            remoteInput.close()
            withTimeout(2_000) { job.join() }
        } finally {
            remoteInput.close()
            clientInput.close()
            socket.close()
            job.cancelAndJoin()
        }
    }
}
