package app.oneclick.tgproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class MtProtoCryptoTest {
    private val secret = ByteArray(16) { (it * 7).toByte() }

    @Test
    fun parsesSecretProtectedClientHandshake() {
        val packet = clientHandshake(MtProtoConstants.TAG_INTERMEDIATE, (-4).toShort())
        val parsed = MtProtoCrypto.parseHandshake(packet, secret)

        assertNotNull(parsed)
        assertEquals(MtProtoConstants.TAG_INTERMEDIATE, parsed!!.protoTag)
        assertEquals((-4).toShort(), parsed.dcIndex)
    }

    @Test
    fun rejectsHandshakeProtectedByAnotherSecret() {
        val packet = clientHandshake(MtProtoConstants.TAG_ABRIDGED, 2)
        assertNull(MtProtoCrypto.parseHandshake(packet, ByteArray(16) { 0x55 }))
    }

    @Test
    fun generatedRelayCarriesProtocolAndDc() {
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_PADDED_INTERMEDIATE, 5)
        val parsed = decryptRelayHeader(relay.initPacket)
        assertEquals(MtProtoConstants.TAG_PADDED_INTERMEDIATE, MtProtoCrypto.readInt32LE(parsed, 56))
        assertEquals(5.toShort(), MtProtoCrypto.readInt16LE(parsed, 60))
    }

    @Test
    fun proxyLinkContainsFakeTlsSecretPrefix() {
        val textSecret = secret.joinToString("") { "%02x".format(it) }
        val config = ProxyConfig(port = 1443, secret = textSecret)
        assertEquals(
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd$textSecret",
            config.proxyLink(),
        )
        assertArrayEquals(secret, config.secretBytes())
    }

    private fun clientHandshake(proto: Int, dc: Short): ByteArray {
        val packet = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val plain = packet.copyOf()
        MtProtoCrypto.writeInt32LE(plain, 56, proto)
        MtProtoCrypto.writeInt16LE(plain, 60, dc)
        val key = MtProtoCrypto.deriveKey(packet.copyOfRange(8, 40), secret)
        val encrypted = AesCtr(key, packet.copyOfRange(40, 56)).update(plain)
        System.arraycopy(encrypted, 56, packet, 56, 8)
        return packet
    }

    private fun decryptRelayHeader(packet: ByteArray): ByteArray {
        val key = packet.copyOfRange(8, 40)
        val iv = packet.copyOfRange(40, 56)
        return AesCtr(key, iv).update(packet)
    }
}
