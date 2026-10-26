package app.oneclick.tgproxy.service

internal const val MIN_SLEEP_BEFORE_RECOVERY_MS = 60_000L
internal const val WAKE_RECOVERY_DEBOUNCE_MS = 10_000L

internal class WakeRecoveryPolicy {
    private var lastRecoveryElapsedMs = 0L
    private var lastWakeSignalElapsedMs = 0L

    fun onWake(idleMs: Long?, recoverWhenSleepUnknown: Boolean, nowElapsedMs: Long): Boolean {
        val duplicate = idleMs == null && lastWakeSignalElapsedMs > 0L &&
            nowElapsedMs - lastWakeSignalElapsedMs < WAKE_RECOVERY_DEBOUNCE_MS
        // Even a short wake consumes the event; its duplicate is not an unknown long sleep.
        lastWakeSignalElapsedMs = nowElapsedMs
        return !duplicate && shouldRecoverAfterWake(
            idleMs, recoverWhenSleepUnknown, nowElapsedMs, lastRecoveryElapsedMs,
        )
    }

    fun recoveryStarted(nowElapsedMs: Long) {
        lastRecoveryElapsedMs = nowElapsedMs
    }
}

/**
 * Recover after a known long sleep, or when the SCREEN_OFF event was missed.
 * Duplicate SCREEN_ON / USER_PRESENT / polling signals are debounced.
 */
internal fun shouldRecoverAfterWake(
    idleMs: Long?,
    recoverWhenSleepUnknown: Boolean,
    nowElapsedMs: Long,
    lastRecoveryElapsedMs: Long,
): Boolean {
    if (lastRecoveryElapsedMs > 0L &&
        nowElapsedMs - lastRecoveryElapsedMs < WAKE_RECOVERY_DEBOUNCE_MS
    ) {
        return false
    }
    return if (idleMs == null) {
        recoverWhenSleepUnknown
    } else {
        idleMs >= MIN_SLEEP_BEFORE_RECOVERY_MS
    }
}
