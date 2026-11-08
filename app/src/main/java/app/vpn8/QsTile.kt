package app.vpn8

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile (the buttons in the status-bar pull-down, next to Wi-Fi, flashlight, ...).
 * Tap = connect / disconnect. Shows the live state while the panel is open.
 */
class QsTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onTileAdded() {
        SettingsStore.init(this)
        render(Engine.status.value.phase, Engine.paused.value)
    }

    override fun onStartListening() {
        SettingsStore.init(this)
        scope?.cancel()
        val sc = CoroutineScope(Dispatchers.Main)
        scope = sc
        sc.launch {
            combine(Engine.status, Engine.paused) { st, paused -> st.phase to paused }
                .collect { (phase, paused) -> render(phase, paused) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    private fun render(p: Phase, paused: Boolean) {
        val t = qsTile ?: return
        t.label = getString(R.string.app_name)
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val (state, text) = when (p) {
            Phase.IDLE -> Tile.STATE_INACTIVE to R.string.disconnected
            Phase.CONNECTING -> Tile.STATE_ACTIVE to R.string.connecting
            Phase.RECONNECTING -> Tile.STATE_ACTIVE to R.string.reconnecting
            Phase.STOPPING -> Tile.STATE_UNAVAILABLE to R.string.stopping
            Phase.CONNECTED ->
                if (paused) Tile.STATE_INACTIVE to R.string.vpn_paused
                else Tile.STATE_ACTIVE to R.string.connected
        }
        t.state = state
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(text)
        t.updateTile()
    }

    override fun onClick() {
        SettingsStore.init(this)
        val st = Engine.status.value.phase
        when {
            st == Phase.STOPPING -> return
            // Core is running but the VPN interface is paused (Stop button in the app): bring it back.
            st == Phase.CONNECTED && Engine.paused.value -> TunnelService.resume(this)
            st != Phase.IDLE -> TunnelService.stop(this)
            else -> connect()
        }
    }

    private fun connect() {
        // VPN permission must be granted from an activity; open the app once, after that the tile works alone.
        if (SettingsStore.current.vpnMode && VpnService.prepare(this) != null) {
            openApp()
        } else {
            runCatching { TunnelService.start(this) }.onFailure { openApp() }
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            startActivityAndCollapse(i)
        }
    }
}
