package com.nuvio.tv.core.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import com.nuvio.connect.protocol.NuvioConnectProtocol
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Local-network transport endpoint for Nuvio Connect.
 *
 * This milestone exposes a health endpoint, version handshake, and DNS-SD
 * advertisement so companion apps can discover the TV on the local network.
 * Pairing/authentication and playback commands remain separate milestones.
 */
object NuvioConnectReceiver {
    private const val TAG = "NuvioConnectReceiver"
    private const val HEALTH_PATH = "/health"
    private const val SOCKET_PATH = "/connect"
    private const val SERVICE_NAME = "NuvioTV"

    @Volatile
    private var server: ReceiverServer? = null

    @Volatile
    private var nsdManager: NsdManager? = null

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return

        try {
            ReceiverServer().also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = it
                Log.i(TAG, "Nuvio Connect receiver listening on port ${NuvioConnectProtocol.DEFAULT_PORT}")
                registerService(context.applicationContext, it.listeningPort)
            }
        } catch (error: IOException) {
            Log.e(TAG, "Unable to start Nuvio Connect receiver", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Nuvio Connect receiver failed to initialize", error)
        }
    }

    @Synchronized
    fun stop() {
        unregisterService()
        server?.stop()
        server = null
    }

    @Synchronized
    private fun registerService(context: Context, port: Int) {
        if (registrationListener != null) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            Log.w(TAG, "NSD service unavailable; receiver remains reachable by direct address")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = NuvioConnectProtocol.SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(TAG, "Nuvio Connect advertised as ${registered.serviceName}.${registered.serviceType} on port ${registered.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Nuvio Connect DNS-SD registration failed: $errorCode")
                synchronized(this@NuvioConnectReceiver) {
                    if (registrationListener === this) registrationListener = null
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Nuvio Connect DNS-SD advertisement removed")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Nuvio Connect DNS-SD unregistration failed: $errorCode")
            }
        }

        nsdManager = manager
        registrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            registrationListener = null
            nsdManager = null
            Log.e(TAG, "Unable to register Nuvio Connect DNS-SD service", error)
        }
    }

    @Synchronized
    private fun unregisterService() {
        val manager = nsdManager
        val listener = registrationListener
        registrationListener = null
        nsdManager = null
        if (manager != null && listener != null) {
            try {
                manager.unregisterService(listener)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Nuvio Connect DNS-SD listener was already unregistered", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to unregister Nuvio Connect DNS-SD service", error)
            }
        }
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
