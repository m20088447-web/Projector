package com.phonecast.android

import android.media.MediaCodec
import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS segmenter for the AVC access units emitted by MediaCodec.
 * Segments begin on IDR frames and carry PAT/PMT tables and presentation timestamps.
 */
/** Receives every 188-byte TS packet as it is produced (for continuous .ts clients). */
fun interface TsPacketListener {
    /** [segmentStart] is true for the PAT packet that begins each IDR-aligned section. */
    fun onPacket(packet: ByteArray, segmentStart: Boolean)
}

class MpegTsHlsMuxer(
    private val publish: (durationSeconds: Double, segment: ByteArray) -> Unit
) {
    private val continuity = HashMap<Int, Int>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var current = ByteArrayOutputStream()
    private var segmentStartUs = -1L
    private var lastPtsUs = -1L
    private var sequenceStarted = false
    private var baseUs = -1L
    private var lastOutUs = -1L

    @Volatile
    var packetListener: TsPacketListener? = null

    @Synchronized
    fun setCodecConfig(data: ByteArray) {
        for (nal in splitNals(data)) {
            when (nalType(nal)) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
    }

    @Synchronized
    fun consume(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            setCodecConfig(data)
            return
        }
        val nals = splitNals(data)
        if (nals.isEmpty()) return
        for (nal in nals) {
            when (nalType(nal)) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        // Zero-based, strictly increasing timestamps: the raw encoder clock starts at device
        // uptime (it can wrap the 33-bit TS clock) and may repeat or step back after a repeated frame.
        if (baseUs < 0) baseUs = presentationTimeUs
        var ptsUs = presentationTimeUs - baseUs + START_OFFSET_US
        if (lastOutUs >= 0 && ptsUs <= lastOutUs) ptsUs = lastOutUs + 1_000L
        lastOutUs = ptsUs
        val isIdr = nals.any { nalType(it) == 5 }
        if (isIdr && current.size() > 0) finishSegment(ptsUs)
        if (isIdr && current.size() == 0) {
            segmentStartUs = ptsUs
            writePsiTables()
            sequenceStarted = true
        }
        if (!sequenceStarted) return

        val accessUnit = ByteArrayOutputStream()
        if (isIdr) {
            if (nals.none { nalType(it) == 7 }) sps?.let { accessUnit.write(startCodeNal(it)) }
            if (nals.none { nalType(it) == 8 }) pps?.let { accessUnit.write(startCodeNal(it)) }
        }
        for (nal in nals) accessUnit.write(startCodeNal(nal))
        val pts90k = (ptsUs * 90L / 1000L) and ((1L shl 33) - 1)
        writeVideoPes(accessUnit.toByteArray(), pts90k)
        lastPtsUs = ptsUs
    }

    @Synchronized
    fun finish() {
        if (current.size() > 0) finishSegment(lastPtsUs)
        sequenceStarted = false
    }

    private fun finishSegment(endUs: Long) {
        val start = segmentStartUs
        val duration = if (start >= 0 && endUs > start) (endUs - start) / 1_000_000.0 else 1.0
        publish(duration.coerceAtLeast(0.25), current.toByteArray())
        current = ByteArrayOutputStream()
        sequenceStarted = false
    }

    private fun writePsiTables() {
        writeSectionPacket(0x0000, byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xF0.toByte(), 0x00
        ), true)
        writeSectionPacket(0x1000, byteArrayOf(
            0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
            0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00
        ))
    }

    private fun writeSectionPacket(pid: Int, sectionWithoutCrc: ByteArray, segmentStart: Boolean = false) {
        val section = sectionWithoutCrc + crc32Mpeg(sectionWithoutCrc)
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or nextContinuity(pid)).toByte()
        packet[4] = 0
        section.copyInto(packet, 5)
        current.write(packet)
        packetListener?.onPacket(packet, segmentStart)
    }

    private fun writeVideoPes(accessUnit: ByteArray, pts90k: Long) {
        if (accessUnit.isEmpty()) return
        val pes = ByteArrayOutputStream()
        pes.write(byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte(), 0x00, 0x00, 0x80.toByte(), 0x80.toByte(), 0x05))
        pes.write(encodePts(pts90k + PTS_DELAY_90K))
        pes.write(accessUnit)
        var offset = 0
        var first = true
        val data = pes.toByteArray()
        while (offset < data.size) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            val remaining = data.size - offset
            val pid = 0x0100
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = pid.toByte()

            val withPcr = first
            var capacity = if (withPcr) 176 else 184
            val payloadLength = minOf(remaining, capacity)
            if (payloadLength < capacity) {
                val adaptationLength = 183 - payloadLength
                packet[3] = (0x30 or nextContinuity(pid)).toByte()
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = if (withPcr) 0x10 else 0x00
                    if (withPcr && adaptationLength >= 7) writePcr(packet, 6, pts90k)
                    for (i in (if (withPcr) 12 else 6) until 5 + adaptationLength) {
                        packet[i] = 0xFF.toByte()
                    }
                }
                val payloadOffset = 5 + adaptationLength
                data.copyInto(packet, payloadOffset, offset, offset + payloadLength)
            } else if (withPcr) {
                packet[3] = (0x30 or nextContinuity(pid)).toByte()
                packet[4] = 7
                packet[5] = 0x10
                writePcr(packet, 6, pts90k)
                data.copyInto(packet, 12, offset, offset + payloadLength)
            } else {
                packet[3] = (0x10 or nextContinuity(pid)).toByte()
                data.copyInto(packet, 4, offset, offset + payloadLength)
            }
            current.write(packet)
            packetListener?.onPacket(packet, false)
            offset += payloadLength
            first = false
        }
    }

    private companion object {
        const val START_OFFSET_US = 1_000_000L
        const val PTS_DELAY_90K = 18_000L // 200 ms between PCR clock and presentation time
    }

    private fun writePcr(packet: ByteArray, offset: Int, pts90k: Long) {
        val base = pts90k and ((1L shl 33) - 1)
        packet[offset] = (base shr 25).toByte()
        packet[offset + 1] = (base shr 17).toByte()
        packet[offset + 2] = (base shr 9).toByte()
        packet[offset + 3] = (base shr 1).toByte()
        packet[offset + 4] = (((base and 1L) shl 7) or 0x7E).toByte()
        packet[offset + 5] = 0
    }

    private fun encodePts(value: Long): ByteArray {
        val pts = value and ((1L shl 33) - 1)
        return byteArrayOf(
            (0x21L or (((pts shr 30) and 0x07L) shl 1)).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 15) and 0x7F) shl 1 or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts and 0x7F) shl 1) or 1).toByte()
        )
    }

    private fun startCodeNal(nal: ByteArray) =
        byteArrayOf(0, 0, 0, 1) + nal

    private fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid] ?: 0
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    private fun splitNals(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 <= bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (bytes[i + 2] == 1.toByte()) {
                    starts.add(i to 3)
                    i += 3
                    continue
                }
                if (i + 4 <= bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    starts.add(i to 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isNotEmpty()) {
            return starts.mapIndexedNotNull { index, start ->
                val from = start.first + start.second
                val until = if (index + 1 < starts.size) starts[index + 1].first else bytes.size
                if (until > from) bytes.copyOfRange(from, until) else null
            }
        }
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            if (length <= 0 || offset + 4 + length > bytes.size) return listOf(bytes)
            result.add(bytes.copyOfRange(offset + 4, offset + 4 + length))
            offset += 4 + length
        }
        return if (offset == bytes.size) result else listOf(bytes)
    }

    private fun crc32Mpeg(data: ByteArray): ByteArray {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(),
            (crc ushr 8).toByte(), crc.toByte()
        )
    }
}

