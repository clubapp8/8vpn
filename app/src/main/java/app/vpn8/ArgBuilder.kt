package app.vpn8

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address

/** Turns the user's settings into Aether command line flags. */
object ArgBuilder {
    fun exitPort(s: Settings): Int = when (s.route) {
        Route.AETHER_PSIPHON -> PSIPHON_PORT
        Route.AETHER_TOR -> TOR_PORT
        else -> s.socksPort
    }

    private fun csv(v: String) = v.split(',', '\n', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    fun build(ctx: Context, s: Settings, fresh: Boolean, psiSlot: Int = 0): List<String> {
        val dir = File(ctx.filesDir, "aether")
        val nl = ctx.applicationInfo.nativeLibraryDir
        val a = ArrayList<String>()
        val r = s.route

        a += listOf("--config", File(dir, "aether.toml").path)
        a += listOf("--bind", "${s.socksBind()}:${s.socksPort}")
        if (s.httpActive) a += listOf("--http-proxy", "${s.httpBind()}:${s.httpPort}")

        when (r) {
            Route.AETHER_PSIPHON -> a += "--psiphon"
            Route.PSIPHON_ONLY -> a += "--psiphon-only"
            Route.PSIPHON_REVERSE -> a += "--psiphon-reverse"
            Route.AETHER_TOR -> a += "--tor"
            Route.TOR_ONLY -> a += "--tor-only"
            Route.TOR_REVERSE -> a += "--tor-reverse"
            Route.AETHER -> {}
        }

        if (!r.noWarp) {
            // Reverse routes carry MASQUE over HTTP/2 only (Tor and Psiphon are TCP only).
            val proto = if (r.reverse) Protocol.MASQUE else s.protocol
            when (proto) {
                Protocol.MASQUE -> a += listOf("--protocol", "masque")
                Protocol.WIREGUARD -> a += listOf("--protocol", "wg")
                Protocol.GOOL -> a += listOf("--protocol", "gool")
                Protocol.GOOL_CLASSIC -> a += "--gool-classic"
                Protocol.MIM -> a += listOf("--protocol", "mim")
            }
            if (r.reverse) a += "--h2"
            else if (proto == Protocol.MASQUE || proto == Protocol.MIM) a += if (s.carrier == "h2") "--h2" else "--h3"

            // Custom gateway: naming a peer makes the core skip scanning. Invalid text is ignored.
            val ep = Endpoints.clean(s.endpoint)
            val ep2 = Endpoints.clean(s.endpoint2)
            val h2 = r.reverse || s.carrier == "h2"
            when (proto) {
                Protocol.MASQUE -> if (ep != null) {
                    a += listOf("--peer", ep)
                    if (h2) a += listOf("--h2-peer", ep)
                }
                Protocol.WIREGUARD -> if (ep != null) a += listOf("--peer", ep)
                Protocol.GOOL -> {
                    if (ep != null) a += listOf("--peer", ep)
                    if (ep2 != null) a += listOf("--gool-peer", ep2)
                }
                Protocol.GOOL_CLASSIC -> {
                    if (ep != null) a += listOf("--wiw-outer", ep)
                    if (ep2 != null) a += listOf("--wiw-inner", ep2)
                }
                Protocol.MIM -> {
                    if (ep != null) a += listOf("--mim-outer", ep)
                    if (ep2 != null) a += listOf("--mim-inner", ep2)
                }
            }

            a += listOf("--scan", s.scan)
            if (s.noize != "default") a += listOf("--noize", s.noize)
            a += when (s.ipVersion) { "6" -> "-6"; "dual" -> "--dual"; else -> "-4" }
            a += if (fresh || !s.quickReconnect) "--no-quick-reconnect" else "--quick-reconnect"
            if (s.dns.isNotBlank()) a += listOf("--dns", csv(s.dns))
        }

        if (s.routeBlock.isNotBlank()) a += listOf("--route-block", csv(s.routeBlock))
        if (s.routeDirect.isNotBlank()) a += listOf("--route-direct", csv(s.routeDirect))
        if (s.upstream.isNotBlank()) a += listOf("--upstream", s.upstream.trim())

        if (r.usesPsiphon) {
            a += listOf("--psiphon-bin", "$nl/libpsiphon.so")
            a += listOf("--psiphon-dir", File(dir, if (psiSlot == 0) "psiphon" else "psiphon-$psiSlot").path)
            if (r != Route.PSIPHON_ONLY) a += listOf("--psiphon-bind", "${s.socksBind()}:$PSIPHON_PORT")
            a += listOf("--psiphon-mode", s.psiphonMode)
            if (s.psiphonRegion.isNotBlank()) a += listOf("--psiphon-region", s.psiphonRegion.uppercase())
            // Psiphon (a Go program) finds no DNS server on Android and tries 127.0.0.1:53, which
            // is refused, so it can never download its server list. Give it real DNS servers.
            // A --psiphon-config the user typed in the extra arguments wins.
            if (!s.extraArgs.contains("--psiphon-config")) {
                psiphonConfig(ctx, s, dir)?.let { a += listOf("--psiphon-config", it.path) }
            }
        }
        if (r.usesTor) {
            a += listOf("--tor-dir", File(dir, "tor").path)
            if (r != Route.TOR_ONLY) a += listOf("--tor-bind", "${s.socksBind()}:$TOR_PORT")
            when (s.torBridges) {
                "always" -> a += "--tor-bridges"
                "never" -> a += "--no-tor-bridges"
                "custom" -> {
                    // The user's own bridge lines go to a file in torrc shape; empty or unusable text means automatic.
                    val txt = TorBridges.fileText(s.torCustomBridges)
                    if (txt.isNotEmpty()) {
                        dir.mkdirs()
                        val f = File(dir, "tor-bridges.txt")
                        f.writeText(txt + "\n")
                        a += listOf("--tor-bridge-file", f.path)
                    }
                }
            }
        }
        if (s.perf != "auto") a += listOf("--perf", s.perf)
        a += splitArgs(s.extraArgs)
        return a
    }

    /**
     * Writes a small Psiphon config laid over Aether's built-in one. It only sets
     * DNSResolverAlternateServers: the phone's own DNS servers first (they work on this very
     * network), then the user's DNS setting, then a few public resolvers.
     */
    private fun psiphonConfig(ctx: Context, s: Settings, dir: File): File? = try {
        val servers = LinkedHashSet<String>()
        servers += systemDns(ctx)
        s.dns.split(',', ' ', ';', '\n').map { it.trim() }.filter { Hosts.valid(it) }.forEach { servers += it }
        servers += listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1", "8.8.4.4")
        val list = servers.filter { it != "0.0.0.0" && !it.startsWith("127.") }.take(8)
        dir.mkdirs()
        val f = File(dir, "psiphon-config.json")
        f.writeText("{\"DNSResolverAlternateServers\":[" + list.joinToString(",") { "\"$it\"" } + "]}\n")
        Engine.log("Psiphon DNS servers: " + list.joinToString(", "))
        f
    } catch (e: Exception) {
        Engine.log("Could not write the Psiphon DNS config: ${e.message}")
        null
    }

    /** IPv4 DNS servers of the phone's real (non-VPN) networks, the active one first. */
    private fun systemDns(ctx: Context): List<String> = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val nets = (cm?.allNetworks ?: emptyArray()).sortedByDescending { it == active }
        nets.filter { n ->
            cm?.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }.flatMap { n ->
            cm?.getLinkProperties(n)?.dnsServers.orEmpty().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** Splits a command line the way a shell would for simple cases: spaces separate, quotes group. */
    fun splitArgs(v: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var has = false
        for (ch in v) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; has = true }
                ch.isWhitespace() -> if (has || sb.isNotEmpty()) { out += sb.toString(); sb.clear(); has = false }
                else -> sb.append(ch)
            }
        }
        if (has || sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
