package app.oneclick.tgproxy.proxy

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BridgeTcpTest {
    private fun socketPair(): Pair<Socket, Socket> = ServerSocket(0).use { listener ->
        val peer = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3_000 }
        listener.accept() to peer
    }

    @Test(timeout = 10_000)
    fun `TCP bridge preserves both cipher streams and cancellation releases reads`() = runBlocking {
        val (client, clientPeer) = socketPair()
        val (remote, remotePeer) = socketPair()
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16)
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_INTERMEDIATE, 2)
        val remoteDecryptor = AesCtr(relay.initPacket.copyOfRange(8, 40), relay.initPacket.copyOfRange(40, 56))
        remoteDecryptor.update(ByteArray(64))
        val reverse = relay.initPacket.copyOfRange(8, 56).reversedArray()
        val remoteEncryptor = AesCtr(reverse.copyOfRange(0, 32), reverse.copyOfRange(32, 48))
        val clientEncryptor = AesCtr(key, iv)
        val clientDecryptor = AesCtr(key, iv)
        val job = launch(Dispatchers.IO) {
            Bridge.bridgeReencrypt(
                client.getInputStream(), client.getOutputStream(),
                Bridge.UpstreamConnection(Bridge.UpstreamType.TCP, tcpSocket = remote),
                CryptoCtx(AesCtr(key, iv), AesCtr(key, iv), relay.telegramDecryptor, relay.telegramEncryptor),
                relay.initPacket, MtProtoConstants.TAG_INTERMEDIATE, 2, false, null,
                closeClient = { client.close() },
            )
        }
        try {
            assertArrayEquals(relay.initPacket, remotePeer.getInputStream().readNBytes(64))
            // Several writes exercise the continuing AES-CTR state, not only the first packet.
            repeat(3) { index ->
                val request = ByteArray(91 + index) { (it + index).toByte() }
                clientPeer.getOutputStream().write(clientEncryptor.update(request))
                assertArrayEquals(request, remoteDecryptor.update(remotePeer.getInputStream().readNBytes(request.size)))
                val response = request.reversedArray()
                remotePeer.getOutputStream().write(remoteEncryptor.update(response))
                assertArrayEquals(response, clientDecryptor.update(clientPeer.getInputStream().readNBytes(response.size)))
            }
            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(client.isClosed)
            assertTrue(remote.isClosed)
            assertEquals(-1, clientPeer.getInputStream().read())
            assertEquals(-1, remotePeer.getInputStream().read())
        } finally {
            listOf(client, clientPeer, remote, remotePeer).forEach { it.close() }
            job.cancelAndJoin()
        }
    }
}
