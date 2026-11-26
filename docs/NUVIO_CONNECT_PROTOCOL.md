# Nuvio Connect protocol (v1)

Nuvio Connect is a local-network companion protocol. The TV owns playback; paired iOS and Android clients send commands and receive authoritative playback snapshots.

## Discovery and transport

- Advertise the TV receiver using DNS-SD service type `_nuvio-connect._tcp.`.
- Default TCP port: `43821` (the implementation may select another available port and advertise it).
- Clients discover the service, resolve its host/port, then open a WebSocket.
- WebSocket frames contain UTF-8 JSON `ConnectEnvelope` objects.
- Every envelope carries protocol `version: 1`, a message `type`, optional `requestId`, and a JSON `payload`.

## Pairing and authorization

1. The TV displays a short-lived pairing code and a QR code containing the receiver endpoint and pairing code.
2. A mobile client sends `pair_request` with its stable installation ID, display name, platform and pairing code.
3. The TV validates the code, registers that client independently, and returns `pair_response` with a per-device access token.
4. Subsequent WebSocket connections authenticate with that token. Tokens are revocable per paired device.
5. Pairing codes expire and are single-use. Never log pairing codes or access tokens.

## Playback control

A client sends `playback_command` with a unique command ID, device identity, action, and optional arguments/media. Supported v1 actions: `play`, `pause`, `toggle`, `seek_to`, `seek_by`, `stop`, and `load`.

The TV serializes accepted commands and remains the source of truth. Every accepted state change increments the snapshot revision and broadcasts a `playback_state` snapshot to every connected paired client. No controller lock is used: any paired phone may issue commands. The snapshot identifies the device that issued the latest accepted command.

## Media handoff

A `load` command includes the stream URL, required HTTP headers, optional subtitle URLs/languages, title and optional content metadata. The TV opens the URL directly in its existing player; the mobile device does not mirror or transcode the stream.

Treat URLs and headers as sensitive. Only accept commands from paired devices and validate incoming URLs before handing them to the player.

## Compatibility

- Unknown message types and unsupported protocol versions must be rejected with a structured error.
- A client must tolerate reconnects and request a fresh playback snapshot after reconnecting.
- Snapshot revisions are monotonically increasing within a TV runtime.
