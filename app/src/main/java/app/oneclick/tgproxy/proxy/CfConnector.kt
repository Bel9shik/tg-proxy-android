package app.oneclick.tgproxy.proxy

import kotlinx.coroutines.*

internal suspend fun connectCfDomains(
    domains: List<String>,
    connect: (String) -> RawWebSocket?,
): Pair<String, RawWebSocket>? {
    // Connect in preference order; a ready socket must not wait for another dial.
    for (domain in domains) {
        currentCoroutineContext().ensureActive()
        val ws = connect(domain) ?: continue
        try {
            currentCoroutineContext().ensureActive()
            return domain to ws
        } catch (error: CancellationException) {
            ws.close()
            throw error
        }
    }
    return null
}
