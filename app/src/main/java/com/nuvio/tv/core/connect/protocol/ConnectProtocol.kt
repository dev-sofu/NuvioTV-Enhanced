package com.nuvio.connect.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract shared by Nuvio TV and the Nuvio mobile apps.
 *
 * Transport: local-network WebSocket.
 * Encoding: UTF-8 JSON.
 */
object NuvioConnectProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_nuvio-connect._tcp."
    const val DEFAULT_PORT = 43821
}

/** Every WebSocket frame is wrapped in this versioned envelope. */
@Serializable
data class ConnectEnvelope(
    val version: Int = NuvioConnectProtocol.VERSION,
    val type: String,
    val requestId: String? = null,
    val payload: kotlinx.serialization.json.JsonObject
)

@Serializable
data class PairingRequest(
    val pairingCode: String,
    val deviceId: String,
    val deviceName: String,
    val platform: ClientPlatform
)

@Serializable
data class PairingResponse(
    val paired: Boolean,
    val accessToken: String? = null,
    val tvDeviceId: String,
    val tvName: String,
    val message: String? = null
)

@Serializable
enum class ClientPlatform {
    @SerialName("ios") IOS,
    @SerialName("android") ANDROID
}

@Serializable
data class PlaybackCommand(
    val commandId: String,
    val deviceId: String,
    val deviceName: String,
    val action: PlaybackAction,
    val positionMs: Long? = null,
    val media: ConnectMedia? = null
)

@Serializable
enum class PlaybackAction {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("toggle") TOGGLE,
    @SerialName("seek_to") SEEK_TO,
    @SerialName("seek_by") SEEK_BY,
    @SerialName("stop") STOP,
    @SerialName("load") LOAD
}

@Serializable
data class ConnectMedia(
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<ConnectSubtitle> = emptyList(),
    val contentId: String? = null,
    val contentType: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val startPositionMs: Long = 0L
)

@Serializable
data class ConnectSubtitle(
    val url: String,
    val language: String? = null,
    val label: String? = null
)

@Serializable
data class PlaybackSnapshot(
    val revision: Long,
    val state: PlaybackState,
    val positionMs: Long,
    val durationMs: Long,
    val media: ConnectMedia? = null,
    val lastCommandDeviceId: String? = null,
    val lastCommandDeviceName: String? = null
)

@Serializable
enum class PlaybackState {
    @SerialName("idle") IDLE,
    @SerialName("loading") LOADING,
    @SerialName("playing") PLAYING,
    @SerialName("paused") PAUSED,
    @SerialName("ended") ENDED,
    @SerialName("error") ERROR
}
