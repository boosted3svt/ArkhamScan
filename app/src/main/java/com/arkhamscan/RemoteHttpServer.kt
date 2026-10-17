package com.arkhamscan

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.*

/** Small, opt-in IPv4 LAN listener; no outbound cleartext policy change is needed. */
class RemoteHttpServer(private val assets: AssetManager, private val session: RemoteSession, port: Int) : NanoHTTPD("0.0.0.0", port) {
    @Volatile var pairCode = secret(6)
        private set
    private var credential: String? = null
    private var expires = 0L
    private var pairFailures = 0
    private var failureWindow = 0L
    private var rateWindow = 0L
    private var requests = 0
    private val ips = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>().filter { !it.isAnyLocalAddress }.map { it.hostAddress!! }.toSet() + "127.0.0.1"
    val addresses get() = ips.filter { it != "127.0.0.1" }.map { "http://$it:$listeningPort" }.ifEmpty { listOf("No LAN IPv4 address. Connect phone to Wi-Fi, then restart web server.") }
    private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val pool = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, SynchronousQueue())
    @Volatile private var stopping = false
    init {
        setAsyncRunner(object : AsyncRunner {
            override fun exec(handler: ClientHandler) {
                clients.add(handler)
                try { pool.execute(handler) } catch (_: RejectedExecutionException) { clients.remove(handler); handler.close() }
            }
            override fun closed(handler: ClientHandler) { clients.remove(handler) }
            override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear() }
        })
    }
    // GZIPOutputStream buffers small SSE events until EOF unless explicitly disabled.
    override fun useGzipWhenAccepted(response: Response): Boolean = response.mimeType != "text/event-stream" && super.useGzipWhenAccepted(response)
    fun startServer() { start(10_000, true) }
    @Synchronized fun revoke() { credential = null; expires = 0; pairCode = secret(6); session.pairingCode.value = pairCode }
    override fun stop() { stopping = true; revoke(); super.stop(); pool.shutdownNow() }
    @Synchronized private fun authorized(req: IHTTPSession): Boolean {
        val value = req.headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("arkham_session=") }?.substringAfter('=')
        return credential != null && System.currentTimeMillis() < expires && equal(value.orEmpty(), credential!!)
    }
    private fun sameOrigin(req: IHTTPSession): Boolean {
        val host = req.headers["host"] ?: return false
        if (host !in ips.map { "$it:$listeningPort" }) return false
        val origin = req.headers["origin"]
        return if (req.method == Method.POST) origin == "http://$host" else origin == null || origin == "http://$host"
    }
    @Synchronized private fun rateAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - rateWindow > 1000) { requests = 0; rateWindow = now }
        return ++requests <= 50
    }
    private fun body(req: IHTTPSession): JSONObject {
        require(req.headers["content-type"].orEmpty().substringBefore(';').trim() == "application/json") { "Expected JSON" }
        val length = req.headers["content-length"]?.toLongOrNull() ?: error("Content-Length required")
        require(length in 2..16_384 && req.headers["transfer-encoding"] == null) { "Request too large or unsupported transfer encoding" }
        val bytes = ByteArray(length.toInt()); var read = 0
        while (read < bytes.size) { val n = req.inputStream.read(bytes, read, bytes.size - read); require(n > 0) { "Incomplete request" }; read += n }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    override fun serve(req: IHTTPSession): Response {
        try {
            if (!sameOrigin(req)) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "Use the phone's displayed IP address and this browser page."))
            if (!rateAllowed()) return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("error", "Too many requests. Wait a moment."))
            if (req.method == Method.GET && req.uri in setOf("/", "/app.js", "/style.css", "/third-party.txt")) {
                val path = when (req.uri) { "/" -> "remote/index.html"; "/app.js" -> "remote/app.js"; "/third-party.txt" -> "remote/third-party.txt"; else -> "remote/style.css" }
                return secure(newFixedLengthResponse(Response.Status.OK, when(req.uri) { "/third-party.txt" -> "text/plain; charset=utf-8"; "/app.js" -> "text/javascript; charset=utf-8"; "/style.css" -> "text/css; charset=utf-8"; else -> "text/html; charset=utf-8" }, assets.open(path).bufferedReader().use { it.readText() }))
            }
            if (req.method == Method.POST && req.uri == "/api/pair") {
                val b = body(req)
                synchronized(this) {
                    val now = System.currentTimeMillis()
                    if (now - failureWindow > 60_000) { pairFailures = 0; failureWindow = now }
                    if (pairFailures >= 10) return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("error", "Pairing paused for one minute after repeated failures."))
                    if (!equal(b.optString("code"), pairCode)) { pairFailures++; return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Incorrect pairing code")) }
                    if (credential != null && now < expires && !authorized(req)) return json(Response.Status.CONFLICT, JSONObject().put("error", "A controller is already paired. Revoke it on the phone or release it in that browser."))
                    credential = secret(32); expires = now + 8 * 60 * 60 * 1000
                    session.connected.value = true; session.changed()
                    return json(Response.Status.OK, JSONObject().put("ok", true)).also {
                        it.addHeader("Set-Cookie", "arkham_session=$credential; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800")
                    }
                }
            }
            if (!authorized(req)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair with the code displayed on the phone."))
            if (req.method == Method.GET) return get(req)

            if (req.method == Method.POST && req.uri == "/api/command") {
                val b = body(req)
                return json(Response.Status.OK, runBlocking { withTimeout(30_000) { session.command(b) } })
            }
            if (req.method == Method.POST && req.uri == "/api/release") { body(req); revoke(); session.connected.value = false; session.changed(); return json(Response.Status.OK, JSONObject().put("ok", true)) }
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Unknown endpoint"))
        } catch (_: TimeoutCancellationException) {
            return json(Response.Status.REQUEST_TIMEOUT, JSONObject().put("error", "Outcome not yet known. Resynchronize; retry mutations only with the same command ID.").put("uncertain", true))
        } catch (e: Exception) {
            return json(Response.Status.CONFLICT, JSONObject().put("error", e.message?.take(300) ?: "Request failed"))
        }
    }
    private fun get(req: IHTTPSession): Response {
        return when (req.uri) {
            "/api/state" -> json(Response.Status.OK, runBlocking { withTimeout(15_000) { session.snapshot() } })
            "/api/events" -> {
                val stream = EventStream(session) { !stopping && authorized(req) }
                secure(newChunkedResponse(Response.Status.OK, "text/event-stream", stream))
            }
            "/api/result" -> json(Response.Status.OK, runBlocking { withTimeout(15_000) { session.commandResult(req.parms["id"].orEmpty()) } })
            "/api/page" -> json(Response.Status.OK, runBlocking { withTimeout(15_000) { session.page(req.parms["kind"].orEmpty(), req.parms["q"].orEmpty(), req.parms["offset"]?.toIntOrNull() ?: 0, req.parms["group"].orEmpty()) } })
            "/api/export" -> {
                val type = req.parms["type"]
                val text = runBlocking { withTimeout(20_000) { withContext(Dispatchers.Main.immediate) {
                    when(type) { "diagnostics" -> session.diagnostics().toString(2); "audit" -> session.exportAudit(); "progress", "outstanding" -> session.exportProgress(req.parms["group"], type == "outstanding"); else -> session.exportCollection() }
                } } }
                secure(newFixedLengthResponse(Response.Status.OK, if (type in setOf("diagnostics", "audit")) "application/json" else "text/csv", text)).also { it.addHeader("Content-Disposition", "attachment; filename=arkhamscan-${if (type in setOf("diagnostics", "audit")) "export.json" else "export.csv"}") }
            }
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Unknown endpoint"))
        }
    }
    private fun json(status: Response.Status, value: JSONObject) = secure(newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()))
    private fun secure(response: Response): Response = response.also {
        it.addHeader("Cache-Control", "no-store")
        it.addHeader("X-Content-Type-Options", "nosniff")
        it.addHeader("Referrer-Policy", "no-referrer")
        it.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        it.addHeader("X-Frame-Options", "DENY")
    }
    companion object {
        private fun secret(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}


/** Coalesced, revisioned snapshots; no queue of camera frames or old commands. */
private class EventStream(private val session: RemoteSession, private val alive: () -> Boolean): InputStream() {
    private var bytes = ByteArray(0)
    private var at = 0
    private var cursor = -1L
    override fun read(): Int {
        if (at < bytes.size) return bytes[at++].toInt() and 255
        if (!alive()) return -1

        val data = runBlocking {
            withTimeoutOrNull(10_000) { session.version.first { it != cursor } }
            session.snapshot()
        }
        if (!alive()) return -1

        cursor = data.getLong("version")
        bytes = "event: state\nid: $cursor\ndata: $data\n\n".toByteArray()
        at = 1
        return bytes[0].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1

        b[off] = first.toByte()
        val n = minOf(len - 1, bytes.size - at)
        bytes.copyInto(b, off + 1, at, at + n)
        at += n
        return n + 1
    }
}
