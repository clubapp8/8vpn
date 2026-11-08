package app.vpn8

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One address and one port that shares the FINAL output of whatever mode is connected
 * (Aether, Aether → Psiphon, Aether → Tor, Psiphon only, Tor only, reverse modes).
 *
 * The same port speaks both SOCKS5 and HTTP (the first byte tells them apart):
 *  - SOCKS5 clients are passed straight through to the exit SOCKS5 port of the current mode.
 *  - HTTP proxy clients (plain requests and CONNECT for HTTPS) are converted to SOCKS5 and sent to that same exit port.
 *
 * Because it always talks to the exit port, the laptop gets Psiphon's or Tor's output, not just the Aether one.
 */
object ShareGateway {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    /** Starts listening. Returns null on success or a short error text. */
    @Synchronized
    fun start(listenHost: String, listenPort: Int, exitHost: String, exitPort: Int): String? {
        stop()
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(listenHost), listenPort), 128)
            }
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        val p = Executors.newCachedThreadPool { r -> Thread(r, "share-gw").apply { isDaemon = true } }
        server = ss
        pool = p
        Engine.log("Share gateway on $listenHost:$listenPort → exit $exitHost:$exitPort")
        Thread({
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: IOException) { break }
                track(c)
                try {
                    p.execute {
                        try { handle(p, c, exitHost, exitPort) } catch (_: Exception) { }
                        close(c)
                    }
                } catch (_: Exception) { close(c) }
            }
        }, "share-gw-accept").apply { isDaemon = true }.start()
        return null
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        val all = synchronized(sockets) { sockets.toList() }
        all.forEach { close(it) }
        runCatching { pool?.shutdownNow() }
        pool = null
    }

    private fun track(s: Socket) { sockets.add(s) }

    private fun close(s: Socket?) {
        if (s == null) return
        sockets.remove(s)
        runCatching { s.close() }
    }

    // ------------------------------------------------------------------ connection

    private fun handle(p: ExecutorService, c: Socket, exitHost: String, exitPort: Int) {
        c.tcpNoDelay = true
        runCatching { c.keepAlive = true }
        c.soTimeout = 20000 // only while the first bytes are read
        val inp = BufferedInputStream(c.getInputStream(), 16 * 1024)
        val out = c.getOutputStream()
        inp.mark(1)
        val first = inp.read()
        if (first < 0) return
        inp.reset()

        // SOCKS client: the exit port already speaks SOCKS5, so just connect the two sockets.
        if (first == 5 || first == 4) {
            val up = Socket()
            track(up)
            try {
                up.connect(InetSocketAddress(exitHost, exitPort), 8000)
                c.soTimeout = 0
                pipe(p, c, inp, out, up, up.getInputStream(), up.getOutputStream())
            } finally { close(up) }
            return
        }

        // HTTP proxy client
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val reqLine = lines[0].split(" ")
        if (reqLine.size < 3) { reply(out, "400 Bad Request"); return }
        val method = reqLine[0]
        val target = reqLine[1]
        val version = reqLine[2]

        if (method.equals("CONNECT", true)) {
            val (host, port) = splitHostPort(target, 443) ?: run { reply(out, "400 Bad Request"); return }
            val up = try { socks5Connect(exitHost, exitPort, host, port) } catch (_: Exception) { reply(out, "502 Bad Gateway"); return }
            try {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush()
                c.soTimeout = 0
                pipe(p, c, inp, out, up, up.getInputStream(), up.getOutputStream())
            } finally { close(up) }
            return
        }

        // Plain HTTP: absolute-form request line ("GET http://host/path HTTP/1.1"), rewritten to origin-form.
        val headerLines = lines.drop(1).filter { it.isNotEmpty() }
        var hostPort: String
        var path: String
        if (target.startsWith("http://", true)) {
            val rest = target.substring(7)
            val slash = rest.indexOf('/')
            hostPort = if (slash >= 0) rest.substring(0, slash) else rest
            path = if (slash >= 0) rest.substring(slash) else "/"
        } else if (target.startsWith("/")) {
            hostPort = headerLines.firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim() ?: run { reply(out, "400 Bad Request"); return }
            path = target
        } else { reply(out, "400 Bad Request"); return }
        hostPort = hostPort.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort, 80) ?: run { reply(out, "400 Bad Request"); return }

        val up = try { socks5Connect(exitHost, exitPort, host, port) } catch (_: Exception) { reply(out, "502 Bad Gateway"); return }
        try {
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            var hasHost = false
            for (h in headerLines) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name == "proxy-connection" || name == "proxy-authorization" || name == "connection" || name == "keep-alive") continue
                if (name == "host") hasHost = true
                sb.append(h).append("\r\n")
            }
            if (!hasHost) sb.append("Host: ").append(hostPort).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            val uo = up.getOutputStream()
            uo.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); uo.flush()
            c.soTimeout = 0
            pipe(p, c, inp, out, up, up.getInputStream(), uo)
        } finally { close(up) }
    }

    private fun reply(out: OutputStream, status: String) {
        runCatching { out.write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush() }
    }

    /** Reads the request head up to the blank line. Anything after it (a request body) stays in the stream. */
    private fun readHead(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        var state = 0
        while (b.size() < 32 * 1024) {
            val x = i.read()
            if (x < 0) return null
            b.write(x)
            state = when {
                x == '\r'.code && (state == 0 || state == 2) -> state + 1
                x == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return String(b.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun splitHostPort(v: String, defaultPort: Int): Pair<String, Int>? {
        val t = v.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) return null
            val host = t.substring(1, end)
            val port = if (t.length > end + 2 && t[end + 1] == ':') t.substring(end + 2).toIntOrNull() ?: return null else defaultPort
            return host to port
        }
        val idx = t.lastIndexOf(':')
        if (idx < 0) return t to defaultPort
        val port = t.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return t.substring(0, idx) to port
    }

    // ------------------------------------------------------------------ SOCKS5 towards the exit

    private fun readFully(i: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) throw IOException("eof")
            n += r
        }
    }

    private val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Opens a connection to host:port through the exit SOCKS5 proxy. Names are sent as names so Psiphon / Tor resolve them remotely. */
    private fun socks5Connect(exitHost: String, exitPort: Int, host: String, port: Int): Socket {
        val s = Socket()
        track(s)
        try {
            s.connect(InetSocketAddress(exitHost, exitPort), 8000)
            runCatching { s.tcpNoDelay = true; s.keepAlive = true }
            s.soTimeout = 60000 // Psiphon and Tor can take a long time to answer a new connection
            val o = s.getOutputStream()
            val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val r = ByteArray(2); readFully(i, r)
            if (r[0] != 5.toByte() || r[1] != 0.toByte()) throw IOException("socks auth")
            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0))
            val lit: InetAddress? = if (v4.matches(host) || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull() else null
            when (lit) {
                is Inet4Address -> { req.write(1); req.write(lit.address) }
                is Inet6Address -> { req.write(4); req.write(lit.address) }
                else -> {
                    val hb = host.toByteArray(Charsets.ISO_8859_1)
                    if (hb.isEmpty() || hb.size > 255) throw IOException("bad host")
                    req.write(3); req.write(hb.size); req.write(hb)
                }
            }
            req.write(port shr 8); req.write(port and 255)
            o.write(req.toByteArray()); o.flush()
            val h = ByteArray(4); readFully(i, h)
            if (h[1] != 0.toByte()) throw IOException("socks refused " + h[1])
            val skip = when (h[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                else -> { val l = ByteArray(1); readFully(i, l); (l[0].toInt() and 0xff) + 2 }
            }
            readFully(i, ByteArray(skip))
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            close(s)
            throw e
        }
    }

    // ------------------------------------------------------------------ copy both ways

    private fun pipe(p: ExecutorService, a: Socket, aIn: InputStream, aOut: OutputStream, b: Socket, bIn: InputStream, bOut: OutputStream) {
        val done = CountDownLatch(1)
        try {
            p.execute {
                if (!copy(bIn, aOut)) { close(a); close(b) } else runCatching { a.shutdownOutput() }
                done.countDown()
            }
        } catch (_: Exception) { return }
        if (!copy(aIn, bOut)) { close(a); close(b) } else runCatching { b.shutdownOutput() }
        runCatching { done.await() }
    }

    /** Copies until the end of the stream. Returns false when it ended with an error (so both sides should be closed). */
    private fun copy(i: InputStream, o: OutputStream): Boolean {
        val buf = ByteArray(16 * 1024)
        return try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }
}
