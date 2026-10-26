package app.oneclick.tgproxy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRecoveryPolicyTest {
    @Test
    fun laterWakeWithMissedScreenOffStillRecovers() {
        val policy = WakeRecoveryPolicy()
        assertFalse(policy.onWake(5_000L, true, 100_000L))
        assertFalse(policy.onWake(null, true, 100_100L))
        assertTrue(policy.onWake(null, true, 300_000L))
    }

    @Test
    fun delayedUnlockDoesNotCauseSecondRecovery() {
        val policy = WakeRecoveryPolicy()
        assertTrue(policy.onWake(120_000L, true, 200_000L))
        policy.recoveryStarted(203_000L)
        assertFalse(policy.onWake(null, false, 230_000L))
        assertTrue(policy.onWake(120_000L, true, 400_000L))
    }

    @Test
    fun duplicateScreenOnAfterShortSleepDoesNotRestartHealthyConnections() {
        val policy = WakeRecoveryPolicy()
        // Polling consumes screenOffAt before Android delivers SCREEN_ON.
        assertFalse(policy.onWake(5_000L, true, 100_000L))
        assertFalse("Duplicate event must not reinterpret a short sleep as unknown",
            policy.onWake(null, true, 100_100L))
    }

    @Test
    fun duplicateScreenOnDoesNotReschedulePendingLongSleepRecovery() {
        val policy = WakeRecoveryPolicy()
        assertTrue(policy.onWake(120_000L, true, 200_000L))
        // Recovery is still waiting for the network, not marked started yet.
        assertFalse(policy.onWake(null, true, 200_500L))
    }

    @Test
    fun recoversAfterKnownLongSleep() {
        assertTrue(
            shouldRecoverAfterWake(
                idleMs = MIN_SLEEP_BEFORE_RECOVERY_MS,
                recoverWhenSleepUnknown = true,
                nowElapsedMs = 120_000L,
                lastRecoveryElapsedMs = 0L,
            )
        )
    }

    @Test
    fun ignoresShortScreenOffPeriod() {
        assertFalse(
            shouldRecoverAfterWake(
                idleMs = 5_000L,
                recoverWhenSleepUnknown = true,
                nowElapsedMs = 120_000L,
                lastRecoveryElapsedMs = 0L,
            )
        )
    }

    @Test
    fun unknownSleepDurationTriggersBackupRecovery() {
        assertTrue(
            shouldRecoverAfterWake(
                idleMs = null,
                recoverWhenSleepUnknown = true,
                nowElapsedMs = 120_000L,
                lastRecoveryElapsedMs = 0L,
            )
        )
    }

    @Test
    fun userPresentDoesNotRestartAfterScreenOnConsumedSleepDuration() {
        assertFalse(
            shouldRecoverAfterWake(
                idleMs = null,
                recoverWhenSleepUnknown = false,
                nowElapsedMs = 120_000L,
                lastRecoveryElapsedMs = 0L,
            )
        )
    }

    @Test
    fun duplicateWakeSignalsAreDebounced() {
        assertFalse(
            shouldRecoverAfterWake(
                idleMs = null,
                recoverWhenSleepUnknown = true,
                nowElapsedMs = 105_000L,
                lastRecoveryElapsedMs = 100_000L,
            )
        )
    }
}
