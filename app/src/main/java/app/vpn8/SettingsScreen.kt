package app.vpn8

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

private fun up(f: (Settings) -> Settings) = SettingsStore.update(f)

@Composable
fun SettingsScreen(
    back: () -> Unit,
    openLogs: () -> Unit,
    openAbout: () -> Unit,
    openSplit: () -> Unit,
    onLanguageChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val s by SettingsStore.flow.collectAsStateWithLifecycle()
    val st by Engine.status.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var regionDialog by remember { mutableStateOf(false) }
    // One section open at a time; tap the header again to close it.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(id: String) { open = if (open == id) null else id }
    val idle = st.phase == Phase.IDLE

    Page(stringResource(R.string.settings), back, actions = {
        IconBtn(Ico.LOGS, openLogs)
        IconBtn(Ico.INFO, openAbout)
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Banner(stringResource(R.string.restart_note))

            // ---- General
            CollapsibleGroup(
                stringResource(R.string.sec_general), open == "general", { toggle("general") },
                subtitle = stringResource(if (s.vpnMode) R.string.mode_vpn else R.string.mode_proxy) + "  ·  " +
                    (if (s.language == "fa") "فارسی" else "English")
            ) {
                Field(stringResource(R.string.language)) {
                    Chips(listOf("en" to "English", "fa" to "فارسی"), s.language) { v ->
                        if (v != s.language) { up { it.copy(language = v) }; onLanguageChanged() }
                    }
                }
                Field(stringResource(R.string.theme)) {
                    Chips(
                        listOf("system" to stringResource(R.string.theme_system), "dark" to stringResource(R.string.theme_dark), "light" to stringResource(R.string.theme_light)),
                        s.theme
                    ) { v -> up { it.copy(theme = v) } }
                }
                if (Build.VERSION.SDK_INT >= 31) SwitchRow(stringResource(R.string.dynamic_color), s.dynamicColor) { v -> up { it.copy(dynamicColor = v) } }
                Field(stringResource(R.string.default_mode)) {
                    Chips(listOf(true to stringResource(R.string.mode_vpn), false to stringResource(R.string.mode_proxy)), s.vpnMode, enabled = idle) { v -> up { it.copy(vpnMode = v) } }
                    if (!idle) Hint(stringResource(R.string.locked_hint))
                }
                Field(stringResource(R.string.tun_engine)) {
                    Chips(listOf("singbox" to "sing-box", "hev" to "hev-socks5-tunnel", "hev-tcp" to "hev (TCP only)"), s.tunEngine) { v -> up { it.copy(tunEngine = v) } }
                }
                SwitchRow(stringResource(R.string.auto_start), s.autoStart) { v -> up { it.copy(autoStart = v) } }
                ActionRow(Ico.BOLT, stringResource(R.string.tile_add)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val sb = ctx.getSystemService(android.app.StatusBarManager::class.java)
                        sb.requestAddTileService(
                            android.content.ComponentName(ctx, QsTile::class.java),
                            ctx.getString(R.string.app_name),
                            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat),
                            ctx.mainExecutor
                        ) { }
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.tile_add_hint), Toast.LENGTH_LONG).show()
                    }
                }
                SwitchRow(stringResource(R.string.keep_awake), s.keepAwake) { v -> up { it.copy(keepAwake = v) } }
                val batteryOk = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                ActionRow(Ico.BOLT, stringResource(if (batteryOk) R.string.battery_row_ok else R.string.battery_row_ask)) {
                    if (batteryOk) {
                        Toast.makeText(ctx, ctx.getString(R.string.battery_already), Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        }
                    }
                }
            }

            // ---- Split tunneling (its own section)
            CollapsibleGroup(
                stringResource(R.string.sec_split), open == "split", { toggle("split") },
                subtitle = when (s.splitMode) {
                    "include" -> stringResource(R.string.split_include) + "  ·  " + stringResource(R.string.split_summary, s.splitApps.size)
                    "exclude" -> stringResource(R.string.split_exclude) + "  ·  " + stringResource(R.string.split_summary, s.splitApps.size)
                    else -> stringResource(R.string.split_off)
                }
            ) {
                Field(stringResource(R.string.split_tunnel)) {
                    Chips(
                        listOf("off" to stringResource(R.string.split_off), "include" to stringResource(R.string.split_include), "exclude" to stringResource(R.string.split_exclude)),
                        s.splitMode
                    ) { v -> up { it.copy(splitMode = v) } }
                }
                if (s.splitMode != "off") {
                    ActionRow(Ico.SETTINGS, stringResource(R.string.split_summary, s.splitApps.size), onClick = openSplit)
                }
                if (!s.vpnMode) Hint(stringResource(R.string.split_vpn_only))
            }

            // ---- Core and protocol
            CollapsibleGroup(
                stringResource(R.string.sec_core), open == "core", { toggle("core") },
                subtitle = s.activeLabel()
            ) {
                Field(stringResource(R.string.protocol)) {
                    Chips(Protocol.values().map { it to it.label }, s.protocol) { v -> up { it.copy(protocol = v) } }
                }
                Field(stringResource(R.string.transport)) {
                    Chips(listOf("h3" to "HTTP/3 (QUIC)", "h2" to "HTTP/2 (TCP)"), s.carrier) { v -> up { it.copy(carrier = v) } }
                }
                Field(stringResource(R.string.scan_level)) {
                    Chips(listOf("turbo", "balanced", "thorough", "verified", "ironclad").map { it to it }, s.scan) { v -> up { it.copy(scan = v) } }
                }
                Field(stringResource(R.string.noize)) {
                    Chips(
                        listOf("default" to stringResource(R.string.noize_default)) + listOf("off", "light", "firewall", "balanced", "gfw", "aggressive").map { it to it },
                        s.noize
                    ) { v -> up { it.copy(noize = v) } }
                }
                Field(stringResource(R.string.ip_version)) {
                    Chips(listOf("4" to "IPv4", "6" to "IPv6", "dual" to "IPv4 + IPv6"), s.ipVersion) { v -> up { it.copy(ipVersion = v) } }
                }
                SwitchRow(stringResource(R.string.quick_reconnect), s.quickReconnect) { v -> up { it.copy(quickReconnect = v) } }
                SwitchRow(stringResource(R.string.fresh_after_drop), s.freshAfterDrop) { v -> up { it.copy(freshAfterDrop = v) } }
                NumberRow(stringResource(R.string.max_tries), s.maxTries) { v -> up { it.copy(maxTries = v) } }
                NumberRow(stringResource(R.string.retry_delay), s.retryDelayMs) { v -> up { it.copy(retryDelayMs = v) } }
                NumberRow(stringResource(R.string.ping_fails), s.pingFails) { v -> up { it.copy(pingFails = v) } }
                NumberRow(stringResource(R.string.ping_interval), s.pingIntervalSec) { v -> up { it.copy(pingIntervalSec = v) } }
                TextRow(
                    stringResource(if (s.protocol == Protocol.GOOL || s.protocol == Protocol.GOOL_CLASSIC || s.protocol == Protocol.MIM) R.string.endpoint_outer else R.string.endpoint),
                    s.endpoint, stringResource(R.string.endpoint_hint), isError = !Endpoints.valid(s.endpoint)
                ) { v -> up { it.copy(endpoint = v.trim()) } }
                if (s.protocol == Protocol.GOOL || s.protocol == Protocol.GOOL_CLASSIC || s.protocol == Protocol.MIM) {
                    TextRow(stringResource(R.string.endpoint_inner), s.endpoint2, stringResource(R.string.endpoint_hint), isError = !Endpoints.valid(s.endpoint2)) { v -> up { it.copy(endpoint2 = v.trim()) } }
                }
                if (!Endpoints.valid(s.endpoint) || !Endpoints.valid(s.endpoint2)) {
                    Hint(stringResource(R.string.endpoint_invalid), error = true)
                } else {
                    Hint(stringResource(R.string.endpoint_note))
                }
                TextRow(stringResource(R.string.upstream), s.upstream, stringResource(R.string.upstream_hint)) { v -> up { it.copy(upstream = v) } }
            }

            // ---- Routing
            CollapsibleGroup(stringResource(R.string.sec_routing), open == "routing", { toggle("routing") }) {
                TextRow(stringResource(R.string.route_block), s.routeBlock, stringResource(R.string.route_hint)) { v -> up { it.copy(routeBlock = v) } }
                TextRow(stringResource(R.string.route_direct), s.routeDirect, stringResource(R.string.route_hint)) { v -> up { it.copy(routeDirect = v) } }
                TextRow(stringResource(R.string.dns), s.dns, stringResource(R.string.dns_hint)) { v -> up { it.copy(dns = v) } }
            }

            // ---- Psiphon
            CollapsibleGroup(stringResource(R.string.sec_psiphon), open == "psiphon", { toggle("psiphon") }) {
                RegionButton(s, true) { regionDialog = true }
                Field(stringResource(R.string.psiphon_mode)) {
                    Chips(listOf("auto" to "Auto", "cdn" to "CDN"), s.psiphonMode) { v -> up { it.copy(psiphonMode = v) } }
                }
            }

            // ---- Tor
            CollapsibleGroup(
                stringResource(R.string.sec_tor), open == "tor", { toggle("tor") },
                subtitle = stringResource(R.string.bridges) + ": " + when (s.torBridges) {
                    "always" -> stringResource(R.string.bridges_always)
                    "never" -> stringResource(R.string.bridges_never)
                    "custom" -> stringResource(R.string.bridges_custom)
                    else -> stringResource(R.string.bridges_auto)
                }
            ) {
                Field(stringResource(R.string.bridges)) {
                    Chips(
                        listOf(
                            "auto" to stringResource(R.string.bridges_auto), "always" to stringResource(R.string.bridges_always),
                            "never" to stringResource(R.string.bridges_never), "custom" to stringResource(R.string.bridges_custom)
                        ),
                        s.torBridges
                    ) { v -> up { it.copy(torBridges = v) } }
                }
                if (s.torBridges == "custom") {
                    val all = TorBridges.lines(s.torCustomBridges)
                    val bad = all.filterNot { TorBridges.usable(it) }
                    TextRow(
                        stringResource(R.string.bridges_custom_title), s.torCustomBridges,
                        stringResource(R.string.bridges_custom_hint), singleLine = false, isError = bad.isNotEmpty() || all.isEmpty()
                    ) { v -> up { it.copy(torCustomBridges = v) } }
                    when {
                        all.isEmpty() -> Hint(stringResource(R.string.bridges_custom_empty), error = true)
                        bad.isNotEmpty() -> Hint(stringResource(R.string.bridges_custom_unsupported, bad.size, bad.map { TorBridges.transport(it) }.distinct().joinToString(", ")), error = true)
                        else -> Hint(stringResource(R.string.bridges_custom_count, all.size))
                    }
                    Hint(stringResource(R.string.bridges_custom_note))
                }
            }

            // ---- Share SOCKS5 / HTTP: ports, sharing and the addresses you can connect to
            val exitPort = ArgBuilder.exitPort(s)
            val socksAll = Hosts.clean(s.socksHost) == "0.0.0.0"
            val httpAll = Hosts.clean(s.httpHost) == "0.0.0.0"
            // Local addresses only work when the proxy listens on this phone's loopback (not shared) or on every network.
            val socksLocalOk = !s.socksShare || socksAll
            val httpLocalOk = s.httpEnabled && (!s.httpShare || httpAll)
            CollapsibleGroup(
                stringResource(R.string.sec_share), open == "share", { toggle("share") },
                subtitle = (if (socksLocalOk) stringResource(R.string.proxy_info, "127.0.0.1:$exitPort") else "SOCKS5") +
                    if (s.httpActive) "  ·  " + stringResource(R.string.http_info, "127.0.0.1:${s.httpPort}") else ""
            ) {
                val socksT = remember(s.socksShare, s.socksHost, st.phase) { if (s.socksShare) LanShare.targets(Hosts.clean(s.socksHost)) else emptyList() }
                val httpT = remember(s.httpShare, s.httpHost, st.phase) { if (s.httpShare) LanShare.targets(Hosts.clean(s.httpHost)) else emptyList() }

                // All current addresses and ports in one highlighted box (tap a line to copy it)
                val lines = buildList {
                    if (socksLocalOk) add(stringResource(R.string.addr_socks_local) to "127.0.0.1:$exitPort")
                    if (httpLocalOk) add(stringResource(R.string.addr_http_local) to "127.0.0.1:${s.httpPort}")
                    for (a in socksT) add(stringResource(R.string.addr_socks_lan, LanShare.kind(a.iface)) to "${a.ip}:$exitPort")
                    for (a in httpT) add(stringResource(R.string.addr_http_lan, LanShare.kind(a.iface)) to "${a.ip}:${s.httpPort}")
                }
                AddrBox(
                    ctx, stringResource(R.string.share_current_title), lines,
                    stringResource(if (st.phase == Phase.CONNECTED) R.string.share_active else R.string.share_inactive)
                )
                if ((s.socksShare || s.httpShare) && socksT.isEmpty() && httpT.isEmpty()) Hint(stringResource(R.string.lan_no_ip))

                // SOCKS5 (always running: the app itself uses it)
                PortRow(stringResource(R.string.socks_port), s.socksPort, if (s.httpActive) s.httpPort else -1, stringResource(R.string.port_invalid)) { v -> up { it.copy(socksPort = v) } }
                SwitchRow(stringResource(R.string.socks_share), s.socksShare) { v -> up { it.copy(socksShare = v) } }
                if (s.socksShare) {
                    TextRow(stringResource(R.string.socks_addr), s.socksHost, "0.0.0.0", isError = !Hosts.valid(s.socksHost)) { v -> up { it.copy(socksHost = v.trim()) } }
                    if (!Hosts.valid(s.socksHost)) Hint(stringResource(R.string.addr_invalid), error = true)
                }

                // HTTP proxy: "this phone" and "other devices" are two separate switches
                SwitchRow(stringResource(R.string.http_proxy), s.httpEnabled) { v -> up { it.copy(httpEnabled = v) } }
                SwitchRow(stringResource(R.string.http_share), s.httpShare) { v -> up { it.copy(httpShare = v) } }
                if (s.httpActive) {
                    PortRow(stringResource(R.string.http_port), s.httpPort, s.socksPort, stringResource(R.string.port_invalid)) { v -> up { it.copy(httpPort = v) } }
                }
                if (s.httpShare) {
                    TextRow(stringResource(R.string.http_addr), s.httpHost, "0.0.0.0", isError = !Hosts.valid(s.httpHost)) { v -> up { it.copy(httpHost = v.trim()) } }
                    if (!Hosts.valid(s.httpHost)) Hint(stringResource(R.string.addr_invalid), error = true)
                }

                if (s.socksShare || s.httpShare) {
                    Hint(stringResource(R.string.lan_howto))
                    Hint(stringResource(R.string.lan_warn), error = true)
                }
                Hint(stringResource(R.string.ports_note))
            }

            // ---- Advanced
            CollapsibleGroup(stringResource(R.string.sec_advanced), open == "advanced", { toggle("advanced") }) {
                Field(stringResource(R.string.perf_profile)) {
                    Chips(listOf("auto" to "Auto", "low" to "Low", "medium" to "Medium", "high" to "High"), s.perf) { v -> up { it.copy(perf = v) } }
                }
                TextRow(stringResource(R.string.extra_args), s.extraArgs, stringResource(R.string.extra_args_hint)) { v -> up { it.copy(extraArgs = v) } }
                NumberRow(stringResource(R.string.tun_mtu), s.tunMtu) { v -> up { it.copy(tunMtu = v) } }
                NumberRow(stringResource(R.string.tcp_buffer), s.tcpBufferKb) { v -> up { it.copy(tcpBufferKb = v) } }
                Hint(stringResource(R.string.tunnel_note))
                ActionRow(Ico.LOGS, stringResource(R.string.open_logs), onClick = openLogs)
            }

            // ---- Clear cache (its own section)
            CollapsibleGroup(
                stringResource(R.string.sec_cache), open == "cache", { toggle("cache") },
                subtitle = stringResource(R.string.clear_cache), danger = true
            ) {
                Hint(stringResource(R.string.clear_cache_warn))
                ActionRow(Ico.TRASH, stringResource(R.string.clear_cache), enabled = idle, danger = true) { confirmClear = true }
                if (!idle) Hint(stringResource(R.string.locked_hint))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (regionDialog) RegionDialog(s.psiphonRegion, onPick = { cc -> up { it.copy(psiphonRegion = cc) }; regionDialog = false }) { regionDialog = false }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.clear_cache), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.clear_cache_warn)) },
            confirmButton = {
                TextButton(onClick = {
                    File(ctx.filesDir, "aether").deleteRecursively()
                    ctx.cacheDir.deleteRecursively()
                    confirmClear = false
                    Toast.makeText(ctx, R.string.cleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}


/** One eye-catching box holding every address and port; tap a line to copy its host:port. */
@Composable
private fun AddrBox(ctx: Context, title: String, lines: List<Pair<String, String>>, status: String) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(c.primary.copy(alpha = 0.30f), c.tertiary.copy(alpha = 0.24f))))
            .border(1.5.dp, c.primary, shape).padding(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c.primary)
        for ((label, addr) in lines) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface.copy(alpha = 0.55f))
                    .clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("proxy", addr))
                        Toast.makeText(ctx, ctx.getString(R.string.copied_addr, addr), Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                Text(addr, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.onSurface)
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
    }
}
