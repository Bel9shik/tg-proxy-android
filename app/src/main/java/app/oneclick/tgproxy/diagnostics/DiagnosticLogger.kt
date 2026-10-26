package app.oneclick.tgproxy.diagnostics

import android.util.Log

/** Lightweight diagnostic facade that never records traffic or proxy secrets. */
object DiagnosticLogger {
    private const val TAG = "TgProxyRuntime"

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(name, fields))
    }

    fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(name, fields) + " error=" + error.javaClass.simpleName)
    }

    private fun format(name: String, fields: Array<out Pair<String, Any?>>): String {
        val safe = fields
            .filterNot { (key, _) -> key.contains("secret", true) || key.contains("payload", true) }
            .joinToString(" ") { (key, value) -> "$key=$value" }
        return if (safe.isEmpty()) name else "$name $safe"
    }
}
