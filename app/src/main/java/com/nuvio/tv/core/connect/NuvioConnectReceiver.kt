package com.nuvio.tv.core.connect

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.CloseCode
import com.nuvio.connect.protocol.NuvioConnectProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Local-network transport endpoint for Nuvio Connect.
 *
 * This first receiver milestone deliberately exposes only a health endpoint and a
 * version handshake. Pairing/authentication and playback commands are separate
 * milestones; no playback command is executed here.
 */
object NuvioConnectReceiver {
    private const val TAG = "NuvioConnectReceiver"
    private const val HEALTH_PATH = "/health"
    private const val SOCKET_PATH = "/connect"

    @Volatile
    private var server: ReceiverServer? = null

    @Synchronized
    fun start() {
        if (server != null) return

        try {
            ReceiverServer().also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = it
                Log.i(TAG, "Nuvio Connect receiver listening on port ${NuvioConnectProtocol.DEFAULT_PORT}")
            }
        } catch (error: IOException) {
            Log.e(TAG, "Unable to start Nuvio Connect receiver", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Nuvio Connect receiver failed to initialize", error)
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private class ReceiverServer : NanoWSD(NuvioConnectProtocol.DEFAULT_PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == HEALTH_PATH) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"service":"nuvio-connect","status":"running","protocolVersion":${NuvioConnectProtocol.VERSION}}"""
                )
            }
            if (session.uri != SOCKET_PATH && isWebsocketRequested(session)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint")
            }
            return super.serve(session)
        }

        override fun openWebSocket(handshake: IHTTPSession): WebSocket {
            return ConnectSocket(handshake)
        }
    }

    private class ConnectSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            send(
                buildJsonObject {
                    put("version", NuvioConnectProtocol.VERSION)
                    put("type", "server_hello")
                    put("requestId", null as String?)
                    put(
                        "payload",
                        buildJsonObject {
                            put("service", "nuvio-connect")
                            put("status", "receiver_ready")
                            put("pairingRequired", true)
                            put("commandsEnabled", false)
                        }
                    )
                }.toString()
            )
        }

        override fun onMessage(message: WebSocketFrame) {
            val response = buildJsonObject {
                put("version", NuvioConnectProtocol.VERSION)
                put("type", "error")
                put("requestId", null as String?)
                put(
                    "payload",
                    buildJsonObject {
                        put("code", "pairing_not_available")
                        put("message", "Pairing is not enabled on this receiver yet.")
                    }
                )
            }
            try {
                send(response.toString())
            } catch (error: IOException) {
                Log.w(TAG, "Could not send receiver response", error)
            }
        }

        override fun onClose(code: CloseCode, reason: String, initiatedByRemote: Boolean) = Unit

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "Connect WebSocket closed with an error", exception)
        }
    }
}
