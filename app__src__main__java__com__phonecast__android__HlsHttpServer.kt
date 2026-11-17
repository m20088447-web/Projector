package com.phonecast.android

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP server on the phone. It serves:
 *  - an Xtream Codes compatible API (so IPTV apps such as Cyber TV can add the phone as a provider),
 *  - a continuous MPEG-TS stream (.ts, lowest latency),
 *  - an HLS playlist with segments (.m3u8).
 */
class HlsHttpServer(
    private val port: Int,
    private val onSegmentReady: () -> Unit
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val segments = ArrayDeque<PublishedSegment>()
    private var nextSequence = 0L
    private val tsClients = CopyOnWriteArrayList<TsClient>()
    val muxer = MpegTsHlsMuxer { duration, bytes -> publishSegment(duration, bytes) }

    init {
        muxer.packetListener = TsPacketListener { packet, segmentStart ->
            broadcastPacket(packet, segmentStart)
        }
    }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        server = ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
        acceptThread = Thread({
            while (active.get()) {
                try {
                    val client = server?.accept() ?: break
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    if (active.get()) Thread.sleep(100)
                }
            }
        }, "PhoneCast-HLS-Acceptor").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun publishSegment(durationSeconds: Double, bytes: ByteArray) {
        segments.addLast(PublishedSegment(nextSequence++, durationSeconds, bytes))
        while (segments.size > SEGMENT_WINDOW) segments.removeFirst()
        onSegmentReady()
    }

    private fun broadcastPacket(packet: ByteArray, segmentStart: Boolean) {
        for (client in tsClients) {
            if (!client.started) {
                if (!segmentStart) continue
                client.started = true
            }
            if (client.queue.size > MAX_QUEUED_PACKETS) {
                client.dead = true
                tsClients.remove(client)
                continue
            }
            client.queue.offer(packet)
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input, 4096) ?: return
            var host = ""
            while (true) {
                val header = readLine(input, 4096) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Host:", ignoreCase = true)) host = header.substring(5).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val target = parts.getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val hostName = host.substringBefore(':').ifEmpty { socket.localAddress?.hostAddress ?: "" }
            val peer = socket.inetAddress?.hostAddress ?: "?"
            RequestLog.add("$peer $method $target")

            val out = BufferedOutputStream(socket.getOutputStream())
            val segmentsInPath = path.split('/').filter { it.isNotEmpty() }
            val streamRequest = parseStreamPath(segmentsInPath)

            when {
                path == "/player_api.php" -> {
                    respond(out, method, "200 OK", "application/json", xtreamResponse(query, hostName).toByteArray(Charsets.UTF_8))
                }
                path == "/get.php" -> {
                    respond(out, method, "200 OK", "audio/x-mpegurl", m3uResponse(query, host.ifEmpty { "$hostName:$port" }).toByteArray(Charsets.UTF_8))
                }
                path == "/xmltv.php" -> {
                    val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv generator-info-name=\"PhoneCast\"></tv>"
                    respond(out, method, "200 OK", "text/xml", xml.toByteArray(Charsets.UTF_8))
                }
                path == "/phonecast.m3u8" || (streamRequest != null && streamRequest == "m3u8") -> {
                    respond(out, method, "200 OK", "application/vnd.apple.mpegurl", playlist().toByteArray(Charsets.UTF_8))
                }
                streamRequest != null -> {
                    streamTransportStream(socket, out, method)
                }
                path.startsWith("/segment-") && path.endsWith(".ts") -> {
                    val sequence = path.removePrefix("/segment-").removeSuffix(".ts").toLongOrNull()
                    val segment = if (sequence == null) null else findSegment(sequence)
                    if (segment == null) {
                        respond(out, method, "404 Not Found", "text/plain", "Segment not available".toByteArray())
                    } else {
                        respond(out, method, "200 OK", "video/mp2t", segment.bytes)
                    }
                }
                else -> {
                    respond(out, method, "200 OK", "text/plain", "PhoneCast HLS server".toByteArray())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /**
     * Recognises Xtream stream URLs: /live/USER/PASS/1.ts, /live/USER/PASS/1.m3u8, /USER/PASS/1(.ts).
     * Returns the extension ("ts" or "m3u8"), or null when the path is not a stream URL.
     */
    private fun parseStreamPath(segs: List<String>): String? {
        val relevant = when {
            segs.size == 4 && segs[0] == "live" -> segs
            segs.size == 3 && segs[0] != "live" && segs[0] != "segment" -> segs
            else -> return null
        }
        val last = relevant.last()
        val id = last.substringBeforeLast('.')
        if (id != STREAM_ID) return null
        return if (last.contains('.')) last.substringAfterLast('.').lowercase(Locale.US) else "ts"
    }

    private fun streamTransportStream(socket: Socket, out: BufferedOutputStream, method: String) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mp2t\r\n" +
                "Cache-Control: no-store, no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
        if (method == "HEAD") return

        val client = TsClient()
        tsClients.add(client)
        RequestLog.add("TV started the live stream (${tsClients.size} viewer)")
        socket.soTimeout = 0
        try {
            val batch = ArrayList<ByteArray>()
            while (active.get() && !client.dead) {
                val first = client.queue.poll(2, TimeUnit.SECONDS)
                if (first == null) {
                    // Idle: a TS null packet keeps the connection honest and detects a closed client.
                    out.write(NULL_PACKET)
                    out.flush()
                    continue
                }
                batch.clear()
                batch.add(first)
                client.queue.drainTo(batch, 255)
                for (packet in batch) out.write(packet)
                out.flush()
            }
        } finally {
            tsClients.remove(client)
            RequestLog.add("TV stopped the live stream")
        }
    }

    private fun xtreamResponse(query: Map<String, String>, hostName: String): String {
        val user = jsonEscape(query["username"] ?: "phone")
        val pass = jsonEscape(query["password"] ?: "phone")
        val now = System.currentTimeMillis() / 1000
        return when (query["action"] ?: "") {
            "" -> {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                "{\"user_info\":{\"username\":\"$user\",\"password\":\"$pass\",\"message\":\"PhoneCast\"," +
                    "\"auth\":1,\"status\":\"Active\",\"exp_date\":\"1999999999\",\"is_trial\":\"0\"," +
                    "\"active_cons\":\"0\",\"created_at\":\"$now\",\"max_connections\":\"5\"," +
                    "\"allowed_output_formats\":[\"m3u8\",\"ts\"]}," +
                    "\"server_info\":{\"url\":\"${jsonEscape(hostName)}\",\"port\":\"$port\",\"https_port\":\"\"," +
                    "\"server_protocol\":\"http\",\"rtmp_port\":\"\",\"timezone\":\"UTC\"," +
                    "\"timestamp_now\":$now,\"time_now\":\"$time\"}}"
            }
            "get_live_categories" -> "[{\"category_id\":\"1\",\"category_name\":\"PhoneCast\",\"parent_id\":0}]"
            "get_live_streams" ->
                "[{\"num\":1,\"name\":\"Phone Screen\",\"stream_type\":\"live\",\"stream_id\":1,\"stream_icon\":\"\"," +
                    "\"epg_channel_id\":\"\",\"added\":\"$now\",\"category_id\":\"1\",\"custom_sid\":\"\"," +
                    "\"tv_archive\":0,\"direct_source\":\"\",\"tv_archive_duration\":0}]"
            "get_short_epg", "get_simple_data_table" -> "{\"epg_listings\":[]}"
            "get_vod_info", "get_series_info" -> "{}"
            else -> "[]"
        }
    }

    private fun m3uResponse(query: Map<String, String>, authority: String): String {
        val user = query["username"] ?: "phone"
        val pass = query["password"] ?: "phone"
        val extension = if (query["output"] == "m3u8" || query["output"] == "hls") "m3u8" else "ts"
        return "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"\" tvg-name=\"Phone Screen\" tvg-logo=\"\" group-title=\"PhoneCast\",Phone Screen\n" +
            "http://$authority/live/$user/$pass/$STREAM_ID.$extension\n"
    }

    private fun respond(out: BufferedOutputStream, method: String, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    @Synchronized
    private fun playlist(): String {
        val copy = segments.toList()
        val target = (copy.maxOfOrNull { it.duration }?.toInt()?.plus(1) ?: 2).coerceAtLeast(2)
        val mediaSequence = copy.firstOrNull()?.sequence ?: nextSequence
        return buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$target\n")
            append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
            for (segment in copy) {
                val duration = String.format(Locale.US, "%.3f", segment.duration)
                append("#EXTINF:$duration,\n")
                append("/segment-${segment.sequence}.ts\n")
            }
        }
    }

    @Synchronized
    private fun findSegment(sequence: Long): PublishedSegment? =
        segments.firstOrNull { it.sequence == sequence }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            try {
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                result[key] = value
            } catch (_: Exception) { }
        }
        return result
    }

    private fun jsonEscape(value: String): String {
        val builder = StringBuilder()
        for (c in value) {
            when {
                c == '"' -> builder.append("\\\"")
                c == '\\' -> builder.append("\\\\")
                c.code < 0x20 -> Unit
                else -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < limit) {
            val next = input.read()
            if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.add(next.toByte())
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        try { server?.close() } catch (_: Exception) { }
        server = null
        acceptThread?.join(500)
        acceptThread = null
        for (client in tsClients) client.dead = true
        workers.shutdownNow()
    }

    private data class PublishedSegment(val sequence: Long, val duration: Double, val bytes: ByteArray)

    private class TsClient {
        val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var started = false
        @Volatile var dead = false
    }

    companion object {
        private const val SEGMENT_WINDOW = 6
        private const val STREAM_ID = "1"
        private const val MAX_QUEUED_PACKETS = 6000 // about 1.1 MB per slow viewer before it is dropped
        private val NULL_PACKET = ByteArray(188) { 0xFF.toByte() }.also {
            it[0] = 0x47
            it[1] = 0x1F
            it[2] = 0xFF.toByte()
            it[3] = 0x10
        }
    }
}
