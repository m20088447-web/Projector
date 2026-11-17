package com.phonecast.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class UdpDiscovery(private val port: Int) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ advertise() }, "PhoneCast-Discovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun advertise() {
        val payload = "PHONECAST_HLS/1".toByteArray(Charsets.US_ASCII)
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), port)
                while (active.get()) {
                    try {
                        socket.send(packet)
                    } catch (_: Exception) { }
                    try {
                        Thread.sleep(900)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (_: Exception) { }
    }

    override fun close() {
        active.set(false)
        thread?.interrupt()
        if (thread !== Thread.currentThread()) thread?.join(500)
    }
}

