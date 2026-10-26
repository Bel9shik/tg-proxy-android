package app.oneclick.tgproxy.service

import android.content.Context
import android.content.SharedPreferences
import app.oneclick.tgproxy.proxy.ProxyConfig as RuntimeProxyConfig
import java.security.SecureRandom

/** Persistent configuration for the single-button local MTProto listener. */
class ProxyConfig(context: Context) {

    companion object {
        private const val SCHEMA_VERSION = 2
        const val TELEGRAM_VALIDATION_VERSION = 3
        private const val DEFAULT_PORT = 1443
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
    private val appState: SharedPreferences =
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    init {
        migrateFromSocksFrontend()
    }

    val port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)

    val secret: String
        get() = prefs.getString("mtproto_secret", null)
            ?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
            ?: generateAndPersistSecret()

    val isTelegramVerified: Boolean
        get() = appState.getInt("telegram_verified_config_version", 0) ==
            TELEGRAM_VALIDATION_VERSION

    fun markTelegramVerified() {
        appState.edit()
            .putInt("telegram_verified_config_version", TELEGRAM_VALIDATION_VERSION)
            .apply()
    }

    fun clearTelegramVerification() {
        appState.edit().remove("telegram_verified_config_version").apply()
    }

    fun runtime(): RuntimeProxyConfig = RuntimeProxyConfig(
        host = "127.0.0.1",
        port = port,
        secret = secret,
        cfProxyEnabled = true,
        cfProxyPriority = true,
        cfProxyFirst = false,
        // shortcut: Android has no idle-socket EOF monitor; connect on demand until the pool has one.
        poolSize = 0,
        cfDomainRefreshEnabled = true,
    )

    private fun migrateFromSocksFrontend() {
        if (prefs.getInt("schema_version", 0) >= SCHEMA_VERSION) return
        val existing = prefs.getString("mtproto_secret", null)
        val secret = existing?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
            ?: randomSecret()
        // apply() updates this process' in-memory preferences synchronously, so
        // the link generated immediately afterwards observes the same secret.
        prefs.edit()
            .putInt("schema_version", SCHEMA_VERSION)
            .putInt("port", DEFAULT_PORT)
            .putString("mtproto_secret", secret)
            .remove("dc_ips")
            .apply()
    }

    private fun generateAndPersistSecret(): String {
        val value = randomSecret()
        prefs.edit().putString("mtproto_secret", value).apply()
        return value
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
