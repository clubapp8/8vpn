package app.vpn8

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the IPv4 addresses other devices can use to reach this phone (hotspot, USB tethering, Wi-Fi). */
object LanShare {
    data class Addr(val iface: String, val ip: String)

    fun addresses(): List<Addr> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filterNot { it.name.startsWith("tun") || it.name.startsWith("dummy") || it.name.startsWith("rmnet") || it.name.startsWith("ccmni") || it.name.startsWith("v4-") || it.name.startsWith("clat") }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { Addr(nif.name, it.hostAddress ?: "") }
            }
            .filter { it.ip.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    /** Addresses to show for a listen address: every device address for 0.0.0.0, the address itself otherwise. */
    fun targets(host: String, all: List<Addr> = addresses()): List<Addr> =
        if (host == "0.0.0.0") all else if (host == "127.0.0.1") emptyList() else listOf(Addr("", host))

    /** Addresses Android uses by default for a hotspot / USB tethering. Shown as a hint while no real address exists yet. */
    val guesses = listOf(Addr("wlan", "192.168.43.1"), Addr("rndis", "192.168.42.129"))

    /** Short human label for an interface name. */
    fun kind(iface: String): String = when {
        iface.startsWith("wlan") || iface.startsWith("swlan") || iface.startsWith("ap") -> "Wi-Fi / Hotspot"
        iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("eth") -> "USB / Ethernet"
        iface.isEmpty() -> "Custom"
        else -> iface
    }
}
