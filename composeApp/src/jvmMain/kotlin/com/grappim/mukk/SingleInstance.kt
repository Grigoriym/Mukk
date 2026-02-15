package com.grappim.mukk

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

private const val SINGLE_INSTANCE_PORT = 51741

class SingleInstance private constructor(val serverSocket: ServerSocket) {

    private val awtWindowRef = AtomicReference<java.awt.Window?>(null)
    @Volatile
    private var onFocusRequest: (() -> Unit)? = null

    fun startListening() {
        val thread = Thread({
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    client.use { it.getInputStream().readAllBytes() }
                    MukkLogger.info("SingleInstance", "Received focus request from another instance")
                    java.awt.EventQueue.invokeLater {
                        awtWindowRef.get()?.toFront()
                        awtWindowRef.get()?.requestFocus()
                        onFocusRequest?.invoke()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }, "mukk-single-instance-listener")
        thread.isDaemon = true
        thread.start()
    }

    fun setWindow(window: java.awt.Window) {
        awtWindowRef.set(window)
    }

    fun setOnFocusRequest(callback: () -> Unit) {
        onFocusRequest = callback
    }

    fun close() {
        serverSocket.close()
    }

    companion object {
        fun tryAcquire(): SingleInstance? {
            return try {
                val socket = ServerSocket(SINGLE_INSTANCE_PORT, 1, InetAddress.getLoopbackAddress())
                SingleInstance(socket)
            } catch (_: Exception) {
                null
            }
        }

        fun notifyExistingInstance() {
            try {
                Socket(InetAddress.getLoopbackAddress(), SINGLE_INSTANCE_PORT).use { socket ->
                    socket.getOutputStream().write("FOCUS\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Existing instance may have just closed — nothing we can do
            }
        }
    }
}
