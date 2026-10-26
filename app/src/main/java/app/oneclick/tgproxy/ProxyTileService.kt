package app.oneclick.tgproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.oneclick.tgproxy.service.ProxyService

/** One-tap Quick Settings switch for the foreground proxy service. */
class ProxyTileService : TileService() {

    companion object {
        fun requestStateRefresh(context: Context) {
            TileService.requestListeningState(
                context,
                ComponentName(context, ProxyTileService::class.java),
            )
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        render(ProxyService.isRunning())
    }

    override fun onClick() {
        super.onClick()
        val running = ProxyService.isRunning()
        try {
            if (running) {
                stopService(Intent(this, ProxyService::class.java))
                render(false)
            } else {
                // This click is an explicit user action. Show the new state
                // immediately; ProxyService requests a refresh if startup fails.
                render(true)
                startForegroundService(Intent(this, ProxyService::class.java))
            }
        } catch (error: Exception) {
            render(running)
            Toast.makeText(
                this,
                error.message ?: getString(R.string.error_proxy_start),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun render(running: Boolean) {
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.quick_settings_tile_label)
            contentDescription = getString(
                if (running) R.string.quick_settings_tile_on else R.string.quick_settings_tile_off,
            )
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = getString(
                    if (running) R.string.quick_settings_tile_on else R.string.quick_settings_tile_off,
                )
            }
            updateTile()
        }
    }
}
