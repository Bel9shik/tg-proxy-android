package app.oneclick.tgproxy.proxy

import android.util.Log
import app.oneclick.tgproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Bridge"

internal class CfCooldownTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val strikes: Int, val blockedUntil: Long, val lastFailureAt: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun isCoolingDown(domain: String): Boolean = (states[domain]?.blockedUntil ?: 0L) > clock()

    fun markRateLimited(domain: String): Long {
        val now = clock()
        val state = states.compute(domain) { _, previous ->
            val strikes = if (previous != null && now - previous.lastFailureAt <= MAX_COOLDOWN_MS) {
                previous.strikes + 1
            } else {
                1
            }
            val delay = cooldownDelayMs(strikes)
            State(strikes, now + delay, now)
        }!!
        return state.blockedUntil - now
    }

    fun markUnavailable(domain: String): Long = markRateLimited(domain)

    fun clear(domain: String) {
        states.remove(domain)
    }

    fun reset() {
        states.clear()
    }

    companion object {
        private const val BASE_COOLDOWN_MS = 45_000L
        private const val MAX_COOLDOWN_MS = 300_000L

        internal fun cooldownDelayMs(strikes: Int): Long {
            var delay = BASE_COOLDOWN_MS
            repeat((strikes - 1).coerceIn(0, 3)) {
                delay = (delay * 2).coerceAtMost(MAX_COOLDOWN_MS)
            }
            return delay
        }
    }
}

/**
 * Bidirectional bridge between client TCP and upstream WebSocket/TCP.
 * Handles re-encryption of MTProto traffic.
 *
 * Matches the Python bridge.py + tg_ws_proxy.py logic including:
 * - WS blacklisting for DCs that always return HTTP redirects
 * - DC fail cooldown (30s) with reduced WS timeout (2s)
 * - MsgSplitter for splitting TCP stream into individual WS frames
 * - CF proxy multi-domain rotation with sticky active domain
 * - TCP fallback using standard DC IPs (not WS redirect IPs)
 * - Per-session stats (up/down bytes/packets, duration)
 */
object Bridge {

    private const val DC_FAIL_COOLDOWN_MS = 30_000L
    private const val WS_FAIL_TIMEOUT_MS = 2_000
    private const val WS_NORMAL_TIMEOUT_MS = 10_000
    private const val TCP_CONNECT_TIMEOUT_MS = 10_000
    private const val DIRECT_IP_FAIL_COOLDOWN_MS = 5 * 60_000L
    private const val DIRECT_WS_CONNECT_TIMEOUT_MS = 3_000

    enum class UpstreamType { WEBSOCKET, CFPROXY, TCP }

    // WS blacklist: DCs that always return redirects (permanent, like Python ws_blacklist)
    @Volatile private var wsBlacklist = ConcurrentHashMap.newKeySet<String>()

    // DC fail cooldown: timestamp until which WS timeout is reduced
    @Volatile private var dcFailUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var directIpFailUntil = ConcurrentHashMap<String, Long>()
    private val cfCooldowns = CfCooldownTracker()

    /**
     * Reset network-specific direct-route state. Cloudflare health is kept
     * across an in-process listener restart: forgetting it after every wake
     * made Telegram retry the same relays that had just returned HTTP 503.
     */
    fun resetState() {
        // Old in-flight attempts keep their old maps and cannot poison the new network.
        wsBlacklist = ConcurrentHashMap.newKeySet()
        dcFailUntil = ConcurrentHashMap()
        directIpFailUntil = ConcurrentHashMap()
        CfDnsResolver.reset()
    }

    /**
     * Get a summary of currently blacklisted DCs (for stats logging, #12).
     */
    fun blacklistSummary(): String {
        val bl = wsBlacklist.toList()
        return if (bl.isEmpty()) "none" else bl.sorted().joinToString(", ") { "DC$it" }
    }

    data class UpstreamConnection(
        val type: UpstreamType,
        val ws: RawWebSocket? = null,
        val tcpSocket: Socket? = null,
    ) : AutoCloseable {
        override fun close() {
            ws?.close()
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Establish upstream connection with fallback chain matching Python:
     * 1. Optionally try CF before every direct route
     * 2. Check WS blacklist / DC not in config -> skip WS, go to fallback
     * 3. Try WS pool hit, then direct WS with an adaptive timeout
     * 4. On WS failure -> update blacklist/cooldown -> fallback (CF proxy / TCP)
     *
     * #1 FIX: Removed outer domain loop — connectToDc() handles domain iteration internally.
     */
    suspend fun connectUpstream(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
    ): UpstreamConnection = withContext(Dispatchers.IO) {
        val wsBlacklist = Bridge.wsBlacklist
        val dcFailUntil = Bridge.dcFailUntil
        val directIpFailUntil = Bridge.directIpFailUntil
        val attemptStartedAt = System.currentTimeMillis()
        val mediaTag = if (isMedia) " media" else ""
        val dcKey = "$dc${if (isMedia) "m" else ""}"
        val cfAttempted = config.cfProxyEnabled && config.cfProxyFirst
        if (cfAttempted) {
            val conn = tryCfProxyFallback(dc, isMedia, config)
            if (conn != null) return@withContext conn
        }

        // If DC not in config or WS blacklisted -> skip WS, go straight to fallback
        val targetIp = config.dcRedirects[dc]
        if (targetIp == null || dcKey in wsBlacklist) {
            if (targetIp == null) {
                Log.i(TAG, "DC$dc not in config -> fallback")
            } else {
                Log.i(TAG, "DC$dc$mediaTag WS blacklisted -> fallback")
            }
            val conn = doFallback(dc, isMedia, config, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
            throw ProxyException("DC$dc$mediaTag no fallback available")
        }

        // Determine WS timeout (adaptive, like Python)
        val now = System.currentTimeMillis()
        val ipFailUntil = directIpFailUntil[targetIp] ?: 0L
        if (config.cfProxyEnabled && now < ipFailUntil) {
            Log.i(TAG, "Direct WS IP $targetIp is cooling down -> fallback")
            val conn = doFallback(dc, isMedia, config, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
        }
        val failUntil = dcFailUntil[dcKey] ?: 0L
        val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT_MS else DIRECT_WS_CONNECT_TIMEOUT_MS

        // 1. Try WS pool
        val pooledWs = pool?.get(dc, isMedia)
        if (pooledWs != null) {
            ProxyStats.poolHits.incrementAndGet()
            ProxyStats.connectionsWs.incrementAndGet()
            if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Pool hit for DC$dc$mediaTag")
            dcFailUntil.remove(dcKey)
            directIpFailUntil.remove(targetIp)
            DiagnosticLogger.event(
                "upstream_route_ready",
                "dc" to dc,
                "media" to isMedia,
                "route" to "ws_pool",
                "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
            )
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = pooledWs)
        }
        ProxyStats.poolMisses.incrementAndGet()

        // 2. Try WS direct — single call to connectToDc which handles domain iteration
        var ws: RawWebSocket? = null
        var anyRedirect = false
        var allRedirects = true

        Log.i(TAG, "DC$dc$mediaTag -> WS direct via $targetIp (timeout=${wsTimeout}ms)")
        DiagnosticLogger.event(
            "upstream_attempt",
            "dc" to dc,
            "media" to isMedia,
            "route" to "ws_direct",
            "timeoutMs" to wsTimeout,
        )
        try {
            ws = RawWebSocket.connectToDc(
                dc = dc,
                isMedia = isMedia,
                targetIp = targetIp,
                bufferSize = config.bufferSize,
                connectTimeoutMs = wsTimeout,
            )
            allRedirects = false
        } catch (e: WsHandshakeError) {
            ProxyStats.wsErrors.incrementAndGet()
            if (e.isRedirect) {
                anyRedirect = true
                Log.w(TAG, "DC$dc$mediaTag got ${e.statusCode} -> ${e.location ?: "?"}")
            } else {
                allRedirects = false
                Log.w(TAG, "DC$dc$mediaTag WS handshake: ${e.statusLine}")
            }
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "handshake",
                "status" to e.statusCode,
            )
        } catch (e: SocketTimeoutException) {
            ProxyStats.wsErrors.incrementAndGet()
            allRedirects = false
            directIpFailUntil[targetIp] = System.currentTimeMillis() + DIRECT_IP_FAIL_COOLDOWN_MS
            Log.w(TAG, "DC$dc$mediaTag WS timeout via $targetIp; IP cooldown enabled")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "timeout",
            )
        } catch (e: Exception) {
            ProxyStats.wsErrors.incrementAndGet()
            allRedirects = false
            Log.w(TAG, "DC$dc$mediaTag WS connect failed: ${e.message}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "connect",
            )
        }

        // WS success
        if (ws != null) {
            dcFailUntil.remove(dcKey)
            directIpFailUntil.remove(targetIp)
            ProxyStats.connectionsWs.incrementAndGet()
            DiagnosticLogger.event(
                "upstream_route_ready",
                "dc" to dc,
                "media" to isMedia,
                "route" to "ws_direct",
                "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
            )
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = ws)
        }

        // WS failed -> update blacklist/cooldown
        if (anyRedirect && allRedirects) {
            wsBlacklist.add(dcKey)
            Log.w(TAG, "DC$dc$mediaTag blacklisted for WS (all redirects)")
        } else {
            dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
            Log.i(TAG, "DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN_MS / 1000}s")
        }

        // 3. Fallback (CF proxy / TCP)
        val conn = doFallback(dc, isMedia, config, allowCf = !cfAttempted)
        if (conn != null) return@withContext conn

        throw ProxyException("All upstream connection attempts failed for DC $dc")
    }

    /**
     * Fallback chain: CF proxy / TCP direct (order based on cfProxyPriority).
     * Matches Python do_fallback().
     */
    private suspend fun doFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        allowCf: Boolean = true,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val methods = mutableListOf<String>()
        methods.add("tcp")

        if (config.cfProxyEnabled && allowCf) {
            if (config.cfProxyPriority) {
                methods.add(0, "cf")  // CF before TCP
            } else {
                methods.add("cf")     // CF after TCP
            }
        }

        for (method in methods) {
            when (method) {
                "cf" -> {
                    DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
                    val conn = tryCfProxyFallback(dc, isMedia, config)
                    if (conn != null) return conn
                }
                "tcp" -> {
                    // TCP fallback uses standard DC IPs (like Python DC_DEFAULT_IPS),
                    // NOT the WS redirect IPs
                    val fallbackIp = MtProtoConstants.DC_IPS[dc]
                    if (fallbackIp != null) {
                        DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "tcp")
                        Log.i(TAG, "DC$dc$mediaTag -> TCP fallback to $fallbackIp:443")
                        val conn = tryTcpDirect(dc, fallbackIp, config.bufferSize)
                        if (conn != null) return conn
                    }
                }
            }
        }
        return null
    }

    /**
     * CF proxy fallback with multi-domain rotation (matches Python _cfproxy_fallback).
     * Tries active domain first, then others. Updates active domain on success.
     */
    private suspend fun tryCfProxyFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val allDomains = if (config.cfProxyUserDomain.isNotBlank()) {
            listOf(config.cfProxyUserDomain)
        } else {
            CfProxyDomains.getDomainsForDc(dc)
        }
        if (allDomains.isEmpty()) return null

        val availableDomains = allDomains.filterNot { cfCooldowns.isCoolingDown(it) }
        if (availableDomains.isEmpty()) {
            Log.i(TAG, "DC$dc$mediaTag -> all CF domains are cooling down")
            return null
        }

        Log.i(TAG, "DC$dc$mediaTag -> trying CF proxy (${availableDomains.size} available)")

        val result = connectCfDomains(availableDomains) { domain ->
            tryCfDomain(dc, isMedia, config.bufferSize, domain)?.second
        } ?: return null
        return cfConnectionReady(dc, isMedia, result.first, result.second)
    }

    private fun tryCfDomain(
        dc: Int,
        isMedia: Boolean,
        bufferSize: Int,
        baseDomain: String,
    ): Pair<String, RawWebSocket>? {
        if (CfDnsResolver.shouldSkipConnectionAttempt()) return null
        val mediaTag = if (isMedia) " media" else ""
        return try {
            val ws = RawWebSocket.connectToDc(
                dc = dc,
                isMedia = isMedia,
                targetIp = baseDomain,
                bufferSize = bufferSize,
                cfProxyDomain = baseDomain,
                connectTimeoutMs = WS_NORMAL_TIMEOUT_MS,
            )
            CfDnsResolver.markAvailable()
            cfCooldowns.clear(baseDomain)
            baseDomain to ws
        } catch (e: Exception) {
            if (shouldCooldownCfDomain(e)) {
                val delay = if (e is WsHandshakeError && e.statusCode == 429) {
                    cfCooldowns.markRateLimited(baseDomain)
                } else {
                    cfCooldowns.markUnavailable(baseDomain)
                }
                Log.w(
                    TAG,
                    "CF proxy $baseDomain cooling down for ${delay / 1000}s after HTTP " +
                        (e as WsHandshakeError).statusCode,
                )
            }
            Log.w(TAG, "DC$dc$mediaTag CF proxy via $baseDomain failed: ${e.message}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "media" to isMedia,
                "route" to "cloudflare",
            )
            null
        }
    }

    private fun cfConnectionReady(
        dc: Int,
        isMedia: Boolean,
        baseDomain: String,
        ws: RawWebSocket,
    ): UpstreamConnection {
        CfProxyDomains.setActiveDomain(dc, baseDomain)
        ProxyStats.connectionsCfProxy.incrementAndGet()
        DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
        return UpstreamConnection(UpstreamType.CFPROXY, ws = ws)
    }

    private fun tryTcpDirect(dc: Int, ip: String, bufferSize: Int): UpstreamConnection? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.connect(InetSocketAddress(ip, 443), TCP_CONNECT_TIMEOUT_MS)
            ProxyStats.connectionsTcpFallback.incrementAndGet()
            DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "route" to "tcp")
            UpstreamConnection(UpstreamType.TCP, tcpSocket = socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            Log.w(TAG, "TCP direct to DC $dc ($ip) failed: ${e.message}")
            DiagnosticLogger.failure("upstream_attempt_failed", e, "dc" to dc, "route" to "tcp")
            null
        }
    }

    /** Flowseal's opaque bidirectional stream: only EOF, errors or cancellation end a session. */
    suspend fun bridgeReencrypt(
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstream: UpstreamConnection,
        cryptoCtx: CryptoCtx,
        relayInit: ByteArray,
        protoTag: Int,
        dc: Int,
        isMedia: Boolean,
        onConnectionVerified: (() -> Unit)?,
        closeClient: () -> Unit,
    ) = coroutineScope {
        val startedAt = System.nanoTime()
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val verified = AtomicBoolean(false)
        val closeReason = AtomicReference("cancelled")
        val ws = upstream.ws
        val tcpOutput = upstream.tcpSocket?.getOutputStream()
        val splitter = if (ws != null) MsgSplitter(protoTag, relayInit) else null

        fun send(data: ByteArray) {
            if (ws != null) ws.sendBinary(data) else {
                tcpOutput!!.write(data)
                tcpOutput.flush()
            }
        }

        fun finish(reason: String) {
            closeReason.compareAndSet("cancelled", reason)
            closeBridgeEndpoints(closeClient, upstream::close)
        }

        try {
            send(relayInit)
            val clientToUpstream = launch(Dispatchers.IO) {
                var reason = "client_eof"
                try {
                    val buffer = ByteArray(65536)
                    while (isActive) {
                        val count = clientInput.read(buffer)
                        if (count < 0) {
                            splitter?.flush()?.forEach { send(it) }
                            break
                        }
                        if (count == 0) continue
                        val plain = cryptoCtx.clientDecryptor.update(buffer.copyOf(count))
                        val encrypted = cryptoCtx.telegramEncryptor.update(plain)
                        if (splitter != null) {
                            val packets = splitter.split(encrypted)
                            if (packets.isNotEmpty()) ws!!.sendBatch(packets)
                        } else send(encrypted)
                        upBytes += count
                        upPackets++
                        ProxyStats.bytesUp.addAndGet(count.toLong())
                    }
                } catch (error: CancellationException) {
                    reason = "cancelled"
                    throw error
                } catch (error: Exception) {
                    reason = "client_io_${error.javaClass.simpleName}"
                } finally {
                    finish(reason)
                }
            }
            val upstreamToClient = launch(Dispatchers.IO) {
                var reason = "upstream_eof"
                try {
                    val buffer = ByteArray(65536)
                    val tcpInput = upstream.tcpSocket?.getInputStream()
                    while (isActive) {
                        val data = if (ws != null) ws.readBinary() ?: break else {
                            val count = tcpInput!!.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            buffer.copyOf(count)
                        }
                        if (data.isEmpty()) continue
                        val plain = cryptoCtx.telegramDecryptor.update(data)
                        clientOutput.write(cryptoCtx.clientEncryptor.update(plain))
                        clientOutput.flush()
                        downBytes += data.size
                        downPackets++
                        ProxyStats.bytesDown.addAndGet(data.size.toLong())
                        if (verified.compareAndSet(false, true)) {
                            try { onConnectionVerified?.invoke() } catch (_: Exception) {}
                        }
                    }
                } catch (error: CancellationException) {
                    reason = "cancelled"
                    throw error
                } catch (error: Exception) {
                    reason = "upstream_io_${error.javaClass.simpleName}"
                } finally {
                    finish(reason)
                }
            }
            try {
                awaitFirstCompletion(clientToUpstream, upstreamToClient)
            } finally {
                finish("cancelled")
                clientToUpstream.cancel()
                upstreamToClient.cancel()
                withContext(NonCancellable) { joinAll(clientToUpstream, upstreamToClient) }
            }
        } finally {
            finish("cancelled")
            DiagnosticLogger.event(
                "session_completed",
                "dc" to dc,
                "media" to isMedia,
                "route" to upstream.type,
                "durationMs" to (System.nanoTime() - startedAt) / 1_000_000,
                "bytesUp" to upBytes,
                "bytesDown" to downBytes,
                "packetsUp" to upPackets,
                "packetsDown" to downPackets,
                "reason" to closeReason.get(),
            )
        }
    }

    private suspend fun awaitFirstCompletion(vararg jobs: Job) {
        try {
            // Wait for first job to complete using select
            val deferreds = jobs.map { job ->
                CompletableDeferred<Unit>().also { d ->
                    job.invokeOnCompletion { d.complete(Unit) }
                }
            }
            // Use kotlinx select to await whichever completes first
            select<Unit> {
                for (d in deferreds) {
                    d.onAwait {}
                }
            }
        } catch (_: Exception) {
        }
    }
}

/** HTTP answers are domain-specific; transport/DNS failures may affect the whole network. */
internal fun shouldCooldownCfDomain(error: Exception): Boolean =
    error is WsHandshakeError && error.statusCode > 0

internal fun closeBridgeEndpoints(closeClient: () -> Unit, closeUpstream: () -> Unit) {
    try { closeClient() } catch (_: Exception) {}
    try { closeUpstream() } catch (_: Exception) {}
}

class ProxyException(message: String, cause: Throwable? = null) : Exception(message, cause)
