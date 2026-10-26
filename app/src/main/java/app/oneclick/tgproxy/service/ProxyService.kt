package app.oneclick.tgproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.oneclick.tgproxy.MainActivity
import app.oneclick.tgproxy.ProxyTileService
import app.oneclick.tgproxy.R
import app.oneclick.tgproxy.diagnostics.DiagnosticLogger
import app.oneclick.tgproxy.proxy.Bridge
import app.oneclick.tgproxy.proxy.CfProxyDomains
import app.oneclick.tgproxy.proxy.TgWsProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground owner of the on-device MTProto -> WSS bridge. */
class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "local_tg_proxy"
        private const val NOTIFICATION_ID = 1
        private const val INTERACTIVE_POLL_INTERVAL_MS = 2_000L
        private const val WAKE_NETWORK_SETTLE_MS = 1_500L
        private const val WAKE_NETWORK_STABLE_MS = 1_000L
        private const val WAKE_NETWORK_WAIT_TIMEOUT_MS = 20_000L
        private const val WAKE_NETWORK_POLL_MS = 250L
        private const val WAKE_LISTENER_DOWN_MS = 1_500L

        const val ACTION_STOP = "app.oneclick.tgproxy.action.STOP"
        const val ACTION_STATE_CHANGED = "app.oneclick.tgproxy.action.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ERROR = "error"
        private const val INTERNAL_PERMISSION =
            "app.oneclick.tgproxy.permission.INTERNAL_STATE"

        @Volatile private var running = false
        @Volatile private var lastError: String? = null

        @JvmStatic fun isRunning(): Boolean = running
        @JvmStatic fun getLastError(): String? = lastError
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyServer: TgWsProxyServer? = null
    private var proxyJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeJob: Job? = null
    private var interactiveWatchJob: Job? = null
    private var wakeRecoveryJob: Job? = null
    private var networkSignature: String? = null
    private val interactiveStateLock = Any()
    private var screenOffAt = 0L
    private val wakeRecoveryPolicy = WakeRecoveryPolicy()
    private var lastInteractive = true
    @Volatile private var destroying = false
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> noteScreenOff()
                Intent.ACTION_SCREEN_ON -> noteScreenOn(
                    source = "screen on",
                    recoverWhenSleepUnknown = true,
                )
                // USER_PRESENT normally follows SCREEN_ON. If SCREEN_ON already
                // consumed screenOffAt, treating this second signal as an
                // "unknown sleep" schedules another listener restart. On a
                // slowly unlocked device that happened after the old 10-second
                // debounce and broke Telegram's freshly reconnected sessions.
                Intent.ACTION_USER_PRESENT -> noteScreenOn(
                    source = "device unlocked",
                    recoverWhenSleepUnknown = false,
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Bridge.resetState()
        createNotificationChannel()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        screenReceiverRegistered = true
        val powerManager = getSystemService(PowerManager::class.java)
        lastInteractive = powerManager.isInteractive
        if (!lastInteractive) {
            screenOffAt = SystemClock.elapsedRealtime()
        }
        startInteractiveStateWatcher(powerManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            destroying = true
            stopProxy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(getString(R.string.notification_starting)))
        startProxy()
        return START_STICKY
    }

    override fun onDestroy() {
        destroying = true
        stopProxy()
        interactiveWatchJob?.cancel()
        interactiveWatchJob = null
        wakeRecoveryJob?.cancel()
        wakeRecoveryJob = null
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInteractiveStateWatcher(powerManager: PowerManager) {
        interactiveWatchJob = serviceScope.launch {
            while (isActive) {
                delay(INTERACTIVE_POLL_INTERVAL_MS)
                val interactive = powerManager.isInteractive
                val changed = synchronized(interactiveStateLock) {
                    interactive != lastInteractive
                }
                if (!changed) continue
                if (interactive) {
                    noteScreenOn(
                        source = "interactive-state fallback",
                        recoverWhenSleepUnknown = true,
                    )
                } else {
                    noteScreenOff()
                }
            }
        }
    }

    private fun noteScreenOff() {
        val now = SystemClock.elapsedRealtime()
        synchronized(interactiveStateLock) {
            lastInteractive = false
            if (screenOffAt == 0L) screenOffAt = now
        }
    }

    private fun noteScreenOn(source: String, recoverWhenSleepUnknown: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val idleMs = synchronized(interactiveStateLock) {
            lastInteractive = true
            val duration = screenOffAt.takeIf { it > 0L }?.let { now - it }
            screenOffAt = 0L
            if (!wakeRecoveryPolicy.onWake(duration, recoverWhenSleepUnknown, now)) return
            duration
        }
        DiagnosticLogger.event("wake_recovery_scheduled", "source" to source, "idleMs" to idleMs)
        scheduleWakeRecovery(source, idleMs)
    }

    private fun scheduleWakeRecovery(
        source: String,
        idleMs: Long?,
    ) {
        wakeRecoveryJob?.cancel()
        wakeRecoveryJob = serviceScope.launch {
            delay(WAKE_NETWORK_SETTLE_MS)
            val powerManager = getSystemService(PowerManager::class.java)
            if (!powerManager.isInteractive) return@launch
            waitForUsableNetworkAfterWake()
            if (!powerManager.isInteractive) return@launch
            val server = proxyServer ?: return@launch
            val reason = idleMs?.let { "$source after ${it / 1000}s sleep" }
                ?: "$source after unknown sleep duration"
            synchronized(interactiveStateLock) {
                wakeRecoveryPolicy.recoveryStarted(SystemClock.elapsedRealtime())
            }
            restartProxyTransportAfterWake(server, reason)
        }
    }

    /**
     * A foreground service can resume before Wi-Fi/cellular data has actually
     * recovered. Restarting the listener at that point makes Telegram spend its
     * reconnect attempt on a route that still has no Internet access. Prefer a
     * validated default network that stays usable briefly, but never wait
     * forever because some devices do not report VALIDATED reliably.
     */
    private suspend fun waitForUsableNetworkAfterWake() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val startedAt = SystemClock.elapsedRealtime()
        var usableSince = 0L
        while (serviceScope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val capabilities = connectivity.activeNetwork?.let {
                connectivity.getNetworkCapabilities(it)
            }
            val usable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable) {
                if (usableSince == 0L) usableSince = now
                if (now - usableSince >= WAKE_NETWORK_STABLE_MS) return
            } else {
                usableSince = 0L
            }
            if (now - startedAt >= WAKE_NETWORK_WAIT_TIMEOUT_MS) {
                DiagnosticLogger.event(
                    "wake_network_wait_timeout",
                    "waitedMs" to now - startedAt,
                )
                return
            }
            delay(WAKE_NETWORK_POLL_MS)
        }
    }

    /**
     * Fully restart the local listener after sleep. Closing only the upstream
     * sockets did not reliably leave Telegram's local proxy backoff state.
     * Briefly closing 127.0.0.1:1443 is equivalent to the successful QS-tile
     * off/on workaround, while the foreground service itself remains alive.
     */
    private suspend fun restartProxyTransportAfterWake(
        expectedServer: TgWsProxyServer,
        reason: String,
    ) {
        if (destroying || proxyServer !== expectedServer) return
        Log.i(TAG, "Fully restarting proxy transport after wake: $reason")
        DiagnosticLogger.event("wake_transport_restart", "reason" to reason)
        updateNotification(getString(R.string.notification_recovering))

        val oldJob = proxyJob
        proxyJob = null
        proxyServer = null
        expectedServer.onStatusChange = null
        expectedServer.onConnectionVerified = null
        unregisterNetworkCallback()
        try {
            expectedServer.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Wake transport stop failed: ${error.message}")
        }
        oldJob?.cancel()
        CfProxyDomains.stopBackgroundRefresh()

        // Keep the port closed long enough for Telegram to observe a real local
        // proxy outage. An immediate close/rebind (usually under 10 ms) did not
        // reliably clear Telegram's backoff, unlike a manual tile off/on cycle.
        delay(WAKE_LISTENER_DOWN_MS)
        if (!destroying) startProxy()
    }

    private fun startProxy() {
        if (destroying || proxyServer != null) return

        val stored = ProxyConfig(this)
        val runtimeConfig = stored.runtime()
        lastError = null
        CfProxyDomains.startBackgroundRefresh()

        val server = TgWsProxyServer(runtimeConfig)
        server.onConnectionVerified = {
            if (!stored.isTelegramVerified) {
                stored.markTelegramVerified()
                Log.i(TAG, "Telegram proxy configuration verified by downstream traffic")
            }
        }
        server.onStatusChange = { listening ->
            if (listening && proxyServer === server) {
                running = true
                lastError = null
                updateNotification(getString(R.string.notification_running, runtimeConfig.port))
                broadcastState()
            }
        }
        proxyServer = server
        registerNetworkCallback(server)
        proxyJob = serviceScope.launch {
            try {
                server.start()
            } catch (error: Throwable) {
                if (proxyServer === server) reportFailure(error)
            }
        }
    }

    private fun reportFailure(error: Throwable) {
        running = false
        lastError = error.message ?: getString(R.string.error_proxy_start)
        Log.e(TAG, "Local MTProto proxy failed", error)
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProxy() {
        val server = proxyServer
        proxyServer = null
        server?.onStatusChange = null
        server?.onConnectionVerified = null
        unregisterNetworkCallback()
        server?.stop()
        proxyJob?.cancel()
        proxyJob = null
        CfProxyDomains.stopBackgroundRefresh()

        val changed = running
        running = false
        lastError = null
        if (changed) broadcastState()
    }

    private fun registerNetworkCallback(server: TgWsProxyServer) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.getNetworkCapabilities(network)?.let {
                    handleNetworkChange(server, network, it)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handleNetworkChange(server, network, capabilities)
            }

            override fun onLost(network: Network) {
                if (proxyServer !== server ||
                    networkSignature?.startsWith("${network.networkHandle}:") != true
                ) return
                // Losing connectivity does not imply a different Wi-Fi/carrier route.
                scheduleNetworkReset(server, clearRouteHealth = false)
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (error: Exception) {
            Log.w(TAG, "Cannot monitor network changes: ${error.message}")
        }
    }

    private fun handleNetworkChange(
        server: TgWsProxyServer,
        network: Network,
        capabilities: NetworkCapabilities,
    ) {
        if (proxyServer !== server) return
        val transports = buildString {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) append('W')
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) append('C')
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append('V')
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) append('E')
        }
        // Validation probes may flap without changing the actual route.
        val signature = "${network.networkHandle}:$transports"
        val previous = networkSignature
        networkSignature = signature
        if (previous == null || previous == signature) return

        scheduleNetworkReset(server)
    }

    private fun scheduleNetworkReset(server: TgWsProxyServer, clearRouteHealth: Boolean = true) {
        networkChangeJob?.cancel()
        networkChangeJob = serviceScope.launch {
            delay(750)
            if (proxyServer === server) server.onNetworkChanged(clearRouteHealth)
        }
    }

    private fun unregisterNetworkCallback() {
        networkChangeJob?.cancel()
        networkChangeJob = null
        // Preserve the network identity across the wake-up listener restart.
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun broadcastState() {
        ProxyTileService.requestStateRefresh(this)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            lastError?.let { putExtra(EXTRA_ERROR, it) }
        }, INTERNAL_PERMISSION)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(
                null,
                getString(R.string.action_stop),
                stopPending
            ).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
