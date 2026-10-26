package app.oneclick.tgproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CfDnsResolverTest {
    @Test
    fun prefersIpv4CandidatesBeforeIpv6() {
        val ordered = orderCfAddresses(
            listOf(
                InetAddress.getByName("2606:4700:3035::ac43:b4a0"),
                InetAddress.getByName("104.21.39.36"),
                InetAddress.getByName("172.67.180.160"),
            ),
        )

        assertEquals(
            listOf("104.21.39.36", "172.67.180.160", "2606:4700:3035:0:0:0:ac43:b4a0"),
            ordered,
        )
    }

    @Test
    fun parsesFirstValidIpv4Answer() {
        val response = """
            {
              "Status": 0,
              "Answer": [
                {"name":"example.com","type":5,"TTL":120,"data":"alias.example.com"},
                {"name":"alias.example.com","type":1,"TTL":240,"data":"104.21.1.2"}
              ]
            }
        """.trimIndent()

        assertEquals(DohAddress("104.21.1.2", 240L), parseDohIpv4(response))
    }

    @Test
    fun rejectsMalformedOrNonAddressAnswers() {
        assertNull(parseDohIpv4("not json"))
        assertNull(parseDohIpv4("""{"Answer":[{"type":28,"data":"2001:db8::1"}]}"""))
        assertNull(parseDohIpv4("""{"Answer":[{"type":1,"data":"999.1.2.3"}]}"""))
    }

    @Test
    fun blocksAllResolutionAttemptsDuringSharedOutageCooldown() {
        var now = 1_000L
        val cooldown = DnsOutageCooldown(
            durationMs = 30_000L,
            probeIntervalMs = 5_000L,
            clock = { now },
        )

        assertFalse(cooldown.isBlocked())
        cooldown.block()
        assertTrue(cooldown.isBlocked())
        assertTrue(cooldown.shouldSkipConnectionAttempt())

        now += 5_000L
        assertFalse(cooldown.shouldSkipConnectionAttempt())
        assertTrue(cooldown.shouldSkipConnectionAttempt())

        now += 24_999L
        assertTrue(cooldown.isBlocked())
        now += 1L
        assertFalse(cooldown.isBlocked())

        cooldown.block()
        cooldown.reset()
        assertFalse(cooldown.isBlocked())
    }
}
