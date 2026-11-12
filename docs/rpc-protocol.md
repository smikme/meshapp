# MeshApp Remote RPC Protocol

**Language:** [Русский](rpc-protocol.ru.md) | English | [Deutsch](rpc-protocol.de.md)

Remote RPC lets one copy of MeshApp control another. For example, MeshApp on a remote computer can receive a request to send a message through its attached radio, carry it out, and return the result. The copy that carries out requests is the **server**, also called the **Host**. The copy that sends requests is the **client**.

RPC means “remote procedure call”: the client names a server function and supplies its parameters. MeshApp writes requests and responses in JSON, a text format with named fields. This is MeshApp's own protocol, not JSON-RPC 2.0. It provides application commands and results rather than direct access to a Meshtastic or MeshCore radio's byte stream.

For connection setup, see the [server launch guide](command-line.md#running-an-rpc-server). The sections below describe the protocol for developers building clients and integrations.

Main source files:

- `src/main/java/com/meshtastic/client/rpc/*` — connections, key verification, message formats, and request handling.
- `src/main/java/com/meshtastic/client/service/RemoteRpcHostService.java` — available server methods.
- `src/main/java/com/meshtastic/client/protocol/rpc/*` — conversion between application data and JSON.
- `src/main/java/com/meshtastic/client/connection/rpc/*` — RPC connections in `ConnectionManager`.

## Terms

| Term | Meaning |
|------|---------|
| Server (Host) | The copy of MeshApp that carries out commands. Its RPC server is enabled, or it is connected to an External RPC Router. |
| Client | The copy of MeshApp that sends commands to the server. In code, its connection uses `ConnectionType.REMOTE_RPC` / `ProtocolType.REMOTE_RPC`. |
| Direct connection | A TCP connection from client to server. The default server address is `127.0.0.1:44030`. |
| Router connection | Client and server connect to an External RPC Router over WebSocket. The router forwards their messages. Client and server verify the access key themselves, and only they can decrypt the RPC data. |
| RPC message (envelope) | A JSON object containing a request, response, or event. An event is a notification from the server, such as an incoming chat message. |
| Frame | A block of data sent as one line over TCP or in `payload.frame` over a router connection. After key verification, it contains an encrypted RPC message. |
| Room | A group of connections on the router. The shared `roomId` tells the router which client and server to connect. |

## Connection Modes

### Direct TCP

Enable the server in the app's settings under **RPC**.

To run it without the desktop window, use `--rpc-server` or run `./gradlew runRpcServer` from source. See [Running an RPC server](command-line.md#running-an-rpc-server) for setup instructions and options.

Default parameters:

| Parameter | Value |
|-----------|-------|
| Listening address | `127.0.0.1` |
| Port | `44030` |
| Access key | A string starting with `mra1_...`. Generate it in settings, or let server mode generate one on startup if no key is set. |

The direct server accepts one client that has passed key verification at a time. While that client is connected, new TCP connections are closed.

First, client and server check that they know the same access key. This check is called authentication. During the check, they exchange unencrypted JSON messages, encoded in UTF-8, with one message per line.

After the check succeeds, RPC messages are encrypted. Each TCP line then carries one encrypted frame in the form `enc1_<base64url>`. The `enc1_` prefix identifies encrypted data; base64url represents the binary data as text.

### External RPC Router

An External RPC Router forwards messages between client and server when a direct TCP connection is not possible. The default cloud router is:

| Parameter | Value |
|-----------|-------|
| Display name | `cloud.meshapp.privatepractice.app` |
| Address | `wss://cloud.meshapp.privatepractice.app` |
| Port | `443` |
| Default path | `/rpc` |

The router connection address has this format:

```text
<ws-or-wss>://<host>:<port>/<path>?roomId=<roomId>&role=<host-or-client>
```

Rules for building the address:

- if the scheme is omitted, `ws://` is used;
- only `ws://` and `wss://` are allowed;
- default port: `8080` for `ws`, `443` for `wss`;
- default path: `/rpc`;
- `roomId` is derived from the access key and does not reveal the key itself;
- `role` is `host` for the Host and `client` for the Remote client.

Example:

```text
ws://router.example.org:8080/rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=client
```

The router sends and receives these JSON messages over WebSocket:

| Direction | JSON | Purpose |
|-----------|------|---------|
| Host -> router | `{"type":"host_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Send a frame to a specific remote client. |
| Client -> router | `{"payload":{"frame":"..."}}` | Send a frame to the active Host in the room. |
| Either side -> router | `{"type":"router_ping"}` | Check that the connection is still working. |
| Router -> Host | `{"type":"host_ready"}` | Host registered in the room. |
| Router -> Host | `{"type":"client_joined","clientSessionId":"..."}` | A new remote client joined the room. |
| Router -> Host | `{"type":"client_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Frame from a remote client. |
| Router -> Host | `{"type":"client_disconnected","clientSessionId":"..."}` | Remote client disconnected. |
| Router -> client | `{"type":"client_ready"}` | Client registered in the room. |
| Router -> client | `{"type":"host_connected"}` | Host is available. |
| Router -> client | `{"type":"host_frame","payload":{"frame":"..."}}` or `{"type":"broadcast","payload":{"frame":"..."}}` | Frame from the Host. |
| Router -> client | `{"type":"host_disconnected"}` | Host disconnected. |
| Router -> either side | `{"type":"router_pong"}` | Reply to a connection check. |
| Router -> either side | `{"type":"router_error","message":"..."}` | Router channel error. |

Client and server send `router_ping` every 15 seconds. If neither `router_pong` nor another router message arrives for more than 45 seconds, the connection is considered lost and is closed.

## Access Key

The same secret access key is configured on client and server. During connection setup, each side proves that it knows this key without sending the key over the network.

Format:

```text
mra1_<base64url-without-padding-32-random-bytes>
```

Properties:

- The key contains 32 random bytes.
- Each authentication attempt uses fresh random values, called nonces, of 24 bytes each.
- Keys and nonces are written as base64url without trailing `=` padding. The access key itself is never sent over the network.
- Authentication proofs and connection encryption keys use calculations based on HMAC-SHA256.
- The room identifier consists of `erpc1_` followed by an HMAC-SHA256 result encoded as base64url without padding.

Room identifier calculation:

```text
roomId = "erpc1_" + base64url_no_padding(HMAC_SHA256(keyBytes, "ERPC-Router room id v1"))
```

## Authentication and Encryption

Direct and router connections use the same version 2 of the secure RPC protocol. With a router, the key-verification messages travel in `payload.frame`. Authentication and encryption still happen between client and server; the router does not receive the access key.

Verification has three steps: the server sends a random value, the client uses the shared key to calculate a proof, and the server then supplies its own proof. The following sections give the exact message formats and calculations.

<a id="step-1-host-challenge"></a>

### Step 1: The server starts verification

The server generates the random value `serverNonce` and sends this unencrypted message:

```json
{
  "type": "auth_challenge",
  "version": 2,
  "cipher": "AES-256-GCM",
  "nonce": "serverNonce"
}
```

<a id="step-2-client-response"></a>

### Step 2: The client proves it knows the key

The client generates its own random value, `clientNonce`. It calculates `proof` from the shared key and both random values, then sends:

```json
{
  "type": "auth_response",
  "version": 2,
  "clientNonce": "clientNonce",
  "proof": "base64urlClientProof"
}
```

The client proof is calculated as:

```text
clientProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-client-auth-v2", serverNonce, clientNonce))
)
```

<a id="step-3-host-okerror"></a>

### Step 3: The server checks the response

If the client proof does not match the expected value, the server reports an error:

```json
{
  "type": "auth_error",
  "message": "invalid access key"
}
```

If the proof is correct, the server returns `auth_ok` with its own proof:

```json
{
  "type": "auth_ok",
  "version": 2,
  "cipher": "AES-256-GCM",
  "proof": "base64urlServerProof"
}
```

The server proof is calculated as:

```text
serverProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-server-auth-v2", serverNonce, clientNonce))
)
```
`transcript(context, serverNonce, clientNonce)` is the byte sequence used to calculate the proof. It contains the context string, followed by the length and contents of each nonce. Here, `utf8` encodes a string as UTF-8, and `uint32_be` writes a four-byte integer with the most significant byte first:
`transcript(context, serverNonce, clientNonce)` is encoded as:

```text
utf8(context)
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

The client checks `serverProof`. Once verification succeeds, both sides independently calculate the same encryption keys for this connection.

<a id="session-key-derivation"></a>

### Calculating encryption keys

First, both nonces are combined into an additional input called the salt (`saltTranscript`):

```text
utf8("meshapp-rpc-secure-v1:salt")
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

Keys are then derived using a process similar to HKDF, which produces keys from a shared secret. Each direction gets its own key and nonce prefix:

```text
prk = HMAC_SHA256(saltTranscript, keyBytes)
clientToServerKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:key", 32)
clientToServerNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:nonce-prefix", 4)
serverToClientKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:key", 32)
serverToClientNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:nonce-prefix", 4)
```
`HKDF-Expand` calculates successive HMAC-SHA256 blocks and joins them until the required length is reached. In these formulas, `||` means joining byte sequences:
`HKDF-Expand` uses HMAC-SHA256 blocks:

```text
T(0) = empty
T(n) = HMAC_SHA256(prk, T(n-1) || info || uint8(n))
output = T(1) || T(2) || ...
```

<a id="encrypted-frames"></a>

### Sending encrypted messages

Each RPC message is encrypted separately, using these parameters:

| Parameter | Value |
|-----------|-------|
| Cipher | `AES/GCM/NoPadding` |
| Key | 32 bytes, with a separate key for each direction |
| Nonce | A 4-byte direction prefix followed by an 8-byte message counter, most significant byte first |
| Message counter (sequence) | Separate for each direction, starting at `0` |
| Additional authenticated data (AAD) | `meshapp-rpc-secure-v1:frame` |
| Integrity tag (GCM tag) | 128 bits |
| Frame prefix | `enc1_` |
| Encoding | base64url without padding |

The transmitted frame contains the encrypted data and integrity tag:

```text
enc1_<base64url_no_padding(ciphertext_plus_gcm_tag)>
```

The connection closes with an error if a frame does not start with `enc1_`, the counter is exhausted, or the GCM integrity check fails.

<a id="rpc-envelope"></a>

## RPC message format

An RPC message is a request, response, or event written as one JSON object. MeshApp uses Gson with `serializeNulls`, so fields can explicitly contain `null`. Before encryption, the JSON is written without indentation or line breaks.

A direct connection sends each encrypted frame on a separate line. A router connection sends the same frame in `payload.frame`. The JSON examples below show messages before encryption, with indentation and line breaks added for readability.

### Request

```json
{
  "type": "rpc_request",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "method": "chat.messages",
  "params": {
    "chatType": "channel",
    "chatKey": "0",
    "limit": 50
  }
}
```

Rules:

- `type` must be `rpc_request`;
- `requestId` is required and cannot be empty or contain only whitespace;
- the client must supply `method`; leading and trailing whitespace is removed before sending;
- `params` must be a JSON object; if missing or of another type, the server uses `{}`;
- the server runs only methods explicitly registered in `RpcMethodRegistry`;
- invalid JSON, non-object messages, unsupported `type` values, and requests without `requestId` are ignored without a response.

### Successful response

```json
{
  "type": "rpc_response",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ok": true,
  "result": {
    "pong": true
  }
}
```

`result` can be an object, array, string, number, boolean, or `null`. Most methods return an object. `requestId` links the response to the original request.

### Error response

```json
{
  "type": "rpc_response",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "ok": false,
  "error": {
    "code": "METHOD_NOT_FOUND",
    "message": "RPC method not found: missing.method"
  }
}
```

Error codes:

| Code | When it occurs |
|------|----------------|
| `BAD_REQUEST` | The request handler threw `IllegalArgumentException`, usually because a parameter was missing or invalid. |
| `METHOD_NOT_FOUND` | Method is not registered on the Host. |
| `CONNECTION_FAILED` | The handler threw `ConnectionException`. If the underlying cause is known, it is included in `message`. |
| `INTERNAL_ERROR` | An unexpected processing error, or a `RpcException` without its own error code. |
| `TIMEOUT` | The client did not receive a response within the allowed time. |
| `TRANSPORT_CLOSED` | The RPC client or connection was closed before or during the call. |

A server handler can supply its own error code through `RpcException`. Leading and trailing whitespace is removed; an empty code becomes `INTERNAL_ERROR`.

### Event

```json
{
  "type": "event",
  "event": "message.incoming",
  "payload": {
    "chatType": "dm",
    "chatKey": "!12345678"
  }
}
```

The server sends events on its own initiative, for example when a chat message arrives. Events have no `requestId` and expect no response. If `payload` is missing, the client passes JSON `null` to its event handlers. Events without a nonempty name in `event` are ignored.

## Timeouts

| Area | Value |
|------|-------|
| Default wait for an RPC response | 30 seconds |
| `system.ping` during connection setup | 5 seconds |
| RPC calls from application forms | Usually 15 seconds |
| Opening a direct TCP connection | 5 seconds |
| Connecting and authenticating through a router | 8 seconds |
| Interval between router connection checks | 15 seconds |
| Allowed time without router messages | 45 seconds |
| Waiting for a field value from a remote Lua form | 2 seconds |
| Retention of an unanswered route request | 365 seconds |

## General JSON Rules

- Required text fields must be JSON strings and must not be empty after leading and trailing whitespace is removed.
- Numbers are read as Java `int`, `long`, `double`, or `float`. Range checks apply only where explicitly documented.
- Boolean fields accept only the JSON values `true` and `false`.
- Missing optional string fields are usually treated as `""`.
- Missing optional number fields are usually treated as `0`.
- Missing optional boolean fields are usually treated as `false`.
- `nodeId` usually has the form `!` + 8 hex characters.
- `nodeNum` is passed as a Java signed `int`; `nodeId` is used for unsigned display.
- Chat and telemetry timestamps count seconds since 1 January 1970, 00:00:00 UTC (Unix time).
- Packet monitor `capturedAt` counts milliseconds since the same instant.
- Protobuf payloads, `publicKey`, `routeData`, and `adminMessage` are encoded with regular Base64, not base64url.

<a id="host-state-model"></a>

## Server data and connections

The client calls methods on the server. These act on the server’s own data and connections:

- `connection.*` manages profiles saved on the server.
- Most `chat.*`, `node.*`, `telemetry.*`, `settings.*`, and `admin.*` methods need a selected, connected device profile on the server.
- When establishing an RPC connection, the client calls `system.ping`. `RemoteRpcState` then holds the `RpcClient` and the result of that check.
- Arbitrary radio bytes cannot be sent through `TransportConnection.sendBytes`. RPC supports only its defined methods.

## Methods

In the descriptions below, **Parameters** means the contents of `params` in a request, and **Result** means the contents of `result` in the response. Use `{}` when no parameters are needed.

If a result refers to another method instead of listing fields, both methods return the same data structure.

The tables use JSON type names: `string` for text, `number` for a number, `boolean` for `true` or `false`, `object` for a JSON object, and `null` for no value. `string[]` or `Node[]` means an array of that type. Field names and values in backticks must be sent exactly as written.

### `system.ping`

Checks Host RPC availability.

Parameters:

```json
{}
```

Result:

| Field | Type | Description |
|-------|------|-------------|
| `app` | string | Always `MeshApp`. |
| `version` | string | Application version. |
| `versionCode` | number | Numeric version code. |
| `remoteRpc` | boolean | Always `true`. |
| `activeConnections` | number | Number of active connections on the Host. |

### `connection.list`

Returns saved Host connections.

Parameters: `{}`.

Result:

| Field | Type | Description |
|-------|------|-------------|
| `selectedConnectionId` | string/null | ID of the selected Host connection. |
| `items` | `Connection[]` | Host connections. |

`Connection`:

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Stable connection profile id. |
| `name` | string | Profile name. |
| `type` | string | `TCP`, `SERIAL`, `BLE`, `REMOTE_RPC`. |
| `protocol` | string | `MESHTASTIC`, `MESHCORE_KISS`, `MESHCORE_COMPANION`, `REMOTE_RPC`. |
| `connected` | boolean | Whether it is currently connected. |
| `reconnecting` | boolean | Whether reconnect is in progress. |
| `selected` | boolean | Whether it is selected on the Host. |
| `nodeId` | string/null | Node id, if known. |
| `address` | string | Human-readable address. |

### `connection.connect`

Connects the Host profile and selects it.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | yes | Connection id. |

Result: the same fields as `connection.list`, plus `connection` if the profile was found.

### `connection.disconnect`

Disconnects a Host profile.

Parameters: `{"id":"..."}`.

Result: the same structure as `connection.connect`.

### `connection.select`

Selects a Host profile without connecting.

Parameters: `{"id":"..."}`.

Result: the same structure as `connection.connect`.

## Chat Methods

The chat API uses:

- `chatType`: `channel` or `dm`;
- `chatKey`: for `channel`, channel index as a string, for example `"0"`; for `dm`, peer `nodeId`, for example `"!12345678"`;
- `ownerNodeId`: local node id of the selected Host connection.

### `chat.list`

Returns the list of channels and direct conversations.

Parameters: `{}`.

Result:

| Field | Type | Description |
|-------|------|-------------|
| `ownerNodeId` | string | Local node id of the Host connection. |
| `connectionId` | string | ID of the selected Host connection. |
| `items` | `ChatItem[]` | Chats. |

`ChatItem`:

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | `CHANNEL` or `DIRECT_MESSAGE`. |
| `displayName` | string | Chat name. |
| `avatarText` | string | Avatar text. |
| `avatarColor` | string | Avatar color, for example `#5B8DEF`. |
| `lastMessageText` | string | Last text. |
| `lastMessageTime` | number | Unix epoch seconds. |
| `unreadCount` | number | Unread count. |
| `channelIndex` | number | Channel index or `0`. |
| `peerNodeId` | string | Peer node id for DM or `""`. |
| `muted` | boolean | Muted flag. |

### `chat.messages`

Loads chat messages.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `chatType` | string | yes | `channel` or `dm`. |
| `chatKey` | string | yes | Channel index string or peer node id. |
| `limit` | number | no | 1..200, default `50`. |
| `beforeDbId` | number | no | If > 0, load messages before this DB id. |
| `afterDbId` | number | no | If > 0 and `beforeDbId == 0`, load messages after this DB id. |

Result:

| Field | Type | Description |
|-------|------|-------------|
| `ownerNodeId` | string | Local node id. |
| `items` | `Message[]` | Messages. |

### `chat.markRead`

Marks a chat as read.

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `chatType` | string | yes |
| `chatKey` | string | yes |

Result: `chat.list`.

### `chat.send`

Sends a message.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `chatType` | string | yes | `channel` or `dm`. |
| `chatKey` | string | yes | Channel index string or peer node id. |
| `text` | string | yes | Non-empty text. |
| `replyId` | number | no | Packet ID of the message being replied to. May be negative. |
| `clientRequestId` | string | no | Identifier to prevent duplicate sends. Repeating a request with the same identifier within 5 minutes returns the message already created. |

Result:

| Field | Type |
|-------|------|
| `message` | `Message` |
| `chat` | `chat.list` result |

The server keeps up to 512 entries to prevent duplicate sends. Each entry is retained for 5 minutes.

### `chat.retry`

Resends an outgoing message whose previous send attempt failed.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `chatType` | string | yes | `channel` or `dm`. |
| `chatKey` | string | yes | Chat key. |
| `dbId` | number | conditional | Message DB id. Requires `dbId > 0` or `packetId != 0`. |
| `packetId` | number | conditional | Message packet id. |

Result: same as `chat.send`.

Only outgoing messages with a send failure can be retried. MeshCore Companion does not support this method.

### `chat.react`

Sends a reaction to a message.

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `chatType` | string | yes |
| `chatKey` | string | yes |
| `targetPacketId` | number | yes, not `0` |
| `emoji` | string | yes |

Result:

| Field | Type |
|-------|------|
| `message` | updated target `Message` |
| `chat` | `chat.list` result |

Limitation: reactions are not supported for the MeshCore Companion runtime.

### `Message`

| Field | Type | Description |
|-------|------|-------------|
| `fromNodeId` | string | Sender node id. |
| `toNodeId` | string | Recipient node id; broadcast is usually `!ffffffff`. |
| `channelIndex` | number | Channel index. |
| `text` | string | Text. |
| `timestamp` | number | Unix epoch seconds. |
| `outgoing` | boolean | Message was sent by the local device. |
| `status` | string/null | `SENDING`, `DELIVERED`, `CONFIRMED`, `FAILED`. |
| `packetId` | number | Meshtastic packet id. |
| `errorReason` | string/null | Send error reason. |
| `replyId` | number | Packet id of the original message. |
| `replyText` | string/null | Text of the original message. |
| `replyToOutgoing` | boolean | Whether the original message was outgoing. |
| `hopStart` | number | Number of forwards through other nodes allowed when the message was sent. |
| `hopLimit` | number | Number of forwards through other nodes still allowed. |
| `rxRssi` | number | RSSI: received radio signal strength. |
| `rxSnr` | number | SNR: signal-to-noise power ratio. |
| `senderName` | string | Resolved sender display name. |
| `viaMqtt` | boolean | Message arrived through MQTT. |
| `systemMessage` | boolean | System message. |
| `dbId` | number | Local DB id. |
| `reactions` | `Reaction[]` | Reactions. |

`Reaction`:

| Field | Type |
|-------|------|
| `targetPacketId` | number |
| `fromNodeId` | string |
| `emoji` | string |
| `timestamp` | number |
| `outgoing` | boolean |
| `dbId` | number |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `senderName` | string |

## Node Methods

### `node.list`

Returns nodes of the selected Host connection.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `includeFavorites` | boolean | no | Include saved favorite nodes even if they are currently offline. |
| `includeIgnored` | boolean | no | Include saved ignored nodes even if they are currently offline. |

Result:

| Field | Type |
|-------|------|
| `ownerNodeId` | string |
| `items` | `Node[]` |

### `node.get`

Returns one node.

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `nodeId` | string | yes |

Result: `{"ownerNodeId":"...","items":[Node]}`.

### `node.traceroute`

Requests the route to a node: the sequence of nodes through which a message travels. The result arrives later in a `node.traceroute.result` event.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `nodeId` | string | conditional | Target node id. |
| `nodeNum` | number | conditional | Target node num. Requires `nodeId` or non-zero `nodeNum`. |
| `requestId` | string | no | If missing, the Host generates a UUID. |

Result:

| Field | Type |
|-------|------|
| `requestId` | string |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `nodeNames` | JSON object mapping unsigned node numbers written as strings to display names |

Available only when the selected protocol handler supports route requests.

### `node.refresh`

Requests fresh UserInfo for a node.

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `nodeNum` | number | yes, not `0` |
| `includeFavorites` | boolean | no |
| `includeIgnored` | boolean | no |

Result: `node.list` with the same `includeFavorites` and `includeIgnored` values.

### `node.delete`

Removes the node from the current connection state and saved cache.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `nodeId` | string | yes | Node id. |
| `nodeNum` | number | no | If non-zero, the node is removed from the current `DeviceState`. |
| `includeFavorites` | boolean | no | Passed to the result `node.list`. |
| `includeIgnored` | boolean | no | Passed to the result `node.list`. |

Result: `node.list`.

### `node.favorite`

Enables/disables the favorite flag.

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `nodeId` | string | yes |
| `enabled` | boolean | no, default `false` |
| `includeFavorites` | boolean | no |
| `includeIgnored` | boolean | no |

Result: `node.list`.

### `node.ignored`

Enables/disables the ignored flag.

Parameters: same as `node.favorite`.

Result: `node.list`.

### `Node`

| Field | Type | Description |
|-------|------|-------------|
| `nodeNum` | number | Java int node number. |
| `nodeId` | string | Stable id in the form `!12345678`. |
| `longName` | string |
| `shortName` | string |
| `latitude` | number |
| `longitude` | number |
| `altitude` | number |
| `snr` | number |
| `lastHeard` | number | Unix epoch seconds or runtime value. |
| `batteryLevel` | number |
| `externallyPowered` | boolean |
| `voltage` | number |
| `channelUtilization` | number |
| `airUtilTx` | number |
| `uptimeSeconds` | number |
| `temperature` | number |
| `relativeHumidity` | number |
| `barometricPressure` | number |
| `hasHopsAway` | boolean |
| `hopsAway` | number |
| `channel` | number |
| `role` | string |
| `hwModel` | string |
| `publicKey` | string | Base64 public key or `""`. |
| `unmessagable` | boolean |
| `licensed` | boolean |
| `favorite` | boolean |
| `ignored` | boolean |

## Telemetry Methods

### `telemetry.dashboard`

Returns the data used to display a node’s telemetry dashboard.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `nodeId` | string | no | If blank, the local node id of the Host connection is used. |
| `sinceEpoch` | number | no | Lower Unix epoch seconds bound, default `0`. |
| `maxFutureTs` | number | no | Upper Unix epoch seconds bound. If `<= 0`, the Host uses `now + 300`. |

Result:

| Field | Type | Description |
|-------|------|-------------|
| `ownerNodeId` | string | Local node id. |
| `nodeId` | string | Node id whose telemetry was loaded. |
| `entries` | `TelemetryEntry[]` | Telemetry for the selected node. |
| `qualityEntries` | `TelemetryEntry[]` | Network quality telemetry. |

Gson converts the Java `TelemetryEntry` object to JSON. Its fields are grouped below by purpose:

- core: `timestamp`, `nodeId`, `telemetryVariant`;
- device metrics: `batteryLevel`, `externallyPowered`, `voltage`, `channelUtilization`, `airUtilTx`, `deviceUptimeSeconds`;
- environment metrics: `temperature`, `relativeHumidity`, `barometricPressure`, `gasResistance`, `environmentVoltage`, `environmentCurrent`, `iaq`, `distance`, `lux`, `whiteLux`, `irLux`, `uvLux`, `windDirection`, `windSpeed`, `weight`, `windGust`, `windLull`, `radiation`, `rainfall1h`, `rainfall24h`, `soilMoisture`, `soilTemperature`, `oneWireTemperatures`;
- air quality metrics: `pm10Standard`, `pm25Standard`, `pm100Standard`, `pm10Environmental`, `pm25Environmental`, `pm100Environmental`, `particles03um`, `particles05um`, `particles10um`, `particles25um`, `particles50um`, `particles100um`, `co2`, `co2Temperature`, `co2Humidity`, `formFormaldehyde`, `formHumidity`, `formTemperature`, `pm40Standard`, `particles40um`, `pmTemperature`, `pmHumidity`, `pmVocIdx`, `pmNoxIdx`, `particlesTps`;
- power metrics: `ch1Voltage`..`ch8Voltage`, `ch1Current`..`ch8Current`;
- local stats: `numPacketsRx`, `numPacketsRxBad`, `numRxDupe`, `numPacketsTx`, `numTxDropped`, `numTxRelay`, `numTxRelayCanceled`, `localUptimeSeconds`, `numOnlineNodes`, `numTotalNodes`, `heapTotalBytes`, `heapFreeBytes`, `noiseFloor`;
- health metrics: `healthHeartBpm`, `healthSpO2`, `healthTemperature`;
- host metrics: `hostUptimeSeconds`, `hostFreememBytes`, `hostDiskfree1Bytes`, `hostDiskfree2Bytes`, `hostDiskfree3Bytes`, `hostLoad1`, `hostLoad5`, `hostLoad15`, `hostUserString`;
- traffic stats: `trafficPacketsInspected`, `trafficPositionDedupDrops`, `trafficNodeinfoCacheHits`, `trafficRateLimitDrops`, `trafficUnknownPacketDrops`, `trafficHopExhaustedPackets`, `trafficRouterHopsPreserved`;
- RF/hops: `rxSnr`, `rxRssi`, `hopStart`, `hopLimit`.

## Packet Monitor Methods

### `packetMonitor.page`

Returns a page of entries from the LoRa packet log.

Parameters:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `request` | string | no | `older` for older entries, `newer` for newer entries. Any other value or an empty string returns the latest entries. |
| `query` | `PacketQuery` | no | Filters to apply to the entries. |
| `cursor` | `PageCursor` | no | Position from which to look for older or newer entries. |
| `limit` | number | no | 1..10000, default `200`. |

`PacketQuery`:

| Field | Type | Description |
|-------|------|-------------|
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL`, or absent. |
| `packetType` | string | Packet type filter. |
| `transportMechanism` | string | Transport filter. |
| `searchText` | string | Full-text filter. |
| `capturedAtFromMillis` | number | Lower capturedAt bound. |
| `capturedAtToMillis` | number | Upper capturedAt bound. |

`PageCursor`:

| Field | Type |
|-------|------|
| `capturedAt` | number |
| `id` | number |

Result:

| Field | Type |
|-------|------|
| `entries` | `PacketLogEntry[]` |
| `hasNewer` | boolean |
| `hasOlder` | boolean |
| `totalMatchingCount` | number |
| `totalStoredCount` | number |

### `packetMonitor.types`

Parameters: `{"query": PacketQuery}`.

Result: `{"items":["TEXT_MESSAGE_APP","POSITION_APP",...]}`.

### `packetMonitor.counts`

Parameters: `{"query": PacketQuery}`.

Result:

| Field | Type |
|-------|------|
| `matching` | number |
| `total` | number |

### `packetMonitor.captureState`

Parameters: `{}`.

Result: `{"enabled": boolean}`.

### `packetMonitor.start`

Starts recording packets in the log.

Parameters: `{}`.

Result: `{"enabled": true}`.

### `packetMonitor.stop`

Stops recording new packets.

Parameters: `{}`.

Result: `{"enabled": false}`.

### `packetMonitor.clear`

Clears the packet log.

Parameters: `{}`.

Result: `{"matching":0,"total":0}`.

### `PacketLogEntry`

A packet log entry. Gson converts the fields of the immutable Java object to JSON:

| Field | Type | Description |
|-------|------|-------------|
| `id` | number | DB id. |
| `ownerNodeId` | string |
| `capturedAt` | number | Unix epoch milliseconds. |
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL`. |
| `packetType` | string |
| `transportMechanism` | string |
| `fromNode` | string |
| `toNode` | string |
| `payloadText` | string |
| `packetBytes` | byte array | JSON array of numbers, using Gson’s representation of a Java `byte[]`. |

## Lua Methods

Lua RPC manages MeshApp IDE scripts on the Host. `scriptId` is required and must be `> 0` in all methods where it is specified.

### `lua.list`

Parameters: `{}`.

Result: `{"items": LuaScript[]}`.

### `lua.get`

Parameters: `{"scriptId": number}`.

Result: `{"script": LuaScript}`.

### `lua.draft`

Creates a draft script.

Parameters: `{}`.

Result: `{"script": LuaScript}`.

### `lua.createDefault`

Creates a script with default values.

Parameters: `{}`.

Result: `{"script": LuaScript}`.

### `lua.create`

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `name` | string | yes |
| `code` | string | no |
| `enabled` | boolean | no |
| `icon` | string | no |
| `nodeId` | string | no |
| `botType` | string | no, default `AIR_BOT` |
| `automationName` | string | no |
| `description` | string | no |
| `author` | string | no |

Result: `{"script": LuaScript}`.

### `lua.save`

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `scriptId` | number | yes |
| `name` | string | yes |
| `code` | string | no |
| `enabled` | boolean | no |

Result: `{"script": LuaScript}`.

### `lua.saveSettings`

Parameters: `scriptId` + `lua.create` fields except `code`.

Result: `{"script": LuaScript}`.

### `lua.delete`

Stops a script and deletes it.

Parameters: `{"scriptId": number}`.

Result: `lua.list`.

### `lua.importJson`

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `json` | string | yes |

Result:

| Field | Type |
|-------|------|
| `script` | `LuaScript` |
| `updated` | boolean |

### `lua.importExport`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `exportFile` | object | Gson serialization of `LuaScriptService.LuaScriptExportFile`. |

Result: same as `lua.importJson`.

### `lua.export`

Parameters: `{"scriptId": number}`.

Result: `{"json": string}`.

### `lua.runningState`

Parameters: `{"scriptId": number}`.

Result:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `running` | boolean |
| `paused` | boolean |

### `lua.run`

Starts the script. For `EXTENSION`, the form is displayed on the client and user actions are sent to the script on the server.

Parameters: `{"scriptId": number}`.

Result: script execution state, as returned by `lua.runningState`.

### `lua.automation.run`

Runs an automation bot command.

Parameters:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `command` | `LuaAutomationCommand` |

`LuaAutomationCommand`:

| Field | Type |
|-------|------|
| `chatType` | string |
| `chatKey` | string |
| `handle` | string |
| `text` | string |
| `arguments` | string |
| `argumentTokens` | string[] |
| `requestId` | string |

Result: script execution state, as returned by `lua.runningState`.

### `lua.ui.nodeSelection`

Passes the user’s node selection back to the script in response to a `lua.ui.nodePick.request` event.

Parameters:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `selected` | boolean |
| `chatType` | string |
| `chatKey` | string |
| `node` | `Node`, optional |

Result: script execution state, as returned by `lua.runningState`.

### `lua.form.event`

Passes a user action on an extension form component to the script.

Parameters:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `componentId` | string |
| `type` | string |
| `value` | any |
| `text` | string |

Result: script execution state, as returned by `lua.runningState`.

### `lua.form.valueResult`

Returns a form component’s value to the server. The client calls this in response to `lua.form.command` with `command="value"`.

Parameters:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `requestId` | string |
| `value` | any |

Result: `{}`.

### `lua.debug`

Starts a script in debug mode.

Parameters:

| Field | Type |
|-------|------|
| `scriptId` | number |
| `breakpoints` | number[] |

Result: script execution state, as returned by `lua.runningState`.

### `lua.stop`

Parameters: `{"scriptId": number}`.

Result: script execution state, as returned by `lua.runningState`.

### `lua.debugContinue`

Parameters: `{"scriptId": number}`.

Result: script execution state, as returned by `lua.runningState`.

### `lua.debugStep`

Parameters: `{"scriptId": number}`.

Result: script execution state, as returned by `lua.runningState`.

### `lua.debugSnapshot`

Parameters: `{"scriptId": number}`.

Result:

| Field | Type | Description |
|-------|------|-------------|
| `snapshot` | `LuaDebugSnapshot`, optional | Missing if there is no snapshot. |

`LuaDebugSnapshot` contains `scriptId`, `line`, `reason`, and `variables`: the script ID, paused line, reason for pausing, and variables. Each `variables[]` entry contains its scope, name, and value as `scope`, `name`, and `value`.

### `lua.kv.list`

Parameters: `{"scriptId": number}`.

Result:

```json
{
  "items": {
    "key": "value"
  }
}
```

### `lua.kv.set`

Parameters:

| Field | Type | Required |
|-------|------|----------|
| `scriptId` | number | yes |
| `key` | string | yes |
| `value` | string | no |

Result: `{}`.

### `lua.kv.delete`

Parameters: `{"scriptId": number, "key": "..."}`.

Result: `{"deleted": boolean}`.

### `lua.kv.clear`

Parameters: `{"scriptId": number}`.

Result: `{}`.

### `LuaScript`

| Field | Type | Description |
|-------|------|-------------|
| `id` | number |
| `guid` | string |
| `icon` | string |
| `name` | string |
| `code` | string |
| `version` | number |
| `description` | string |
| `author` | string |
| `enabled` | boolean |
| `nodeId` | string |
| `botType` | string | `AIR_BOT`, `AUTOMATION_BOT`, `EXTENSION`. |
| `automationName` | string |
| `createdAt` | number |
| `updatedAt` | number |
| `lastRunAt` | number |
| `lastStatus` | string |
| `lastError` | string/null |
| `running` | boolean | Included only when script execution state was queried while building the response. |
| `paused` | boolean | Included only when script execution state was queried while building the response. |

### `LuaFormComponentSpec`

An extension form component is described by a JSON object with these fields:

`id`, `type`, `parentId`, `text`, `prompt`, `value`, `items`, `min`, `max`, `disabled`, `visible`, `style`, `orientation`, `width`, `height`, `minWidth`, `minHeight`, `maxWidth`, `maxHeight`, `readOnly`, `wrap`, `monospace`, `grow`, `rows`, `chartType`, `xLabel`, `yLabel`, `xType`, `legend`, `symbols`, `series`.

`series[]` describes chart data series with `name`, `color`, and `points`. Each item in `points[]` contains coordinates `x` and `y`.

## Settings Methods

The `settings.*` methods work with the radio attached through the server’s selected profile. Before many operations, MeshApp tries to obtain a temporary administration key from the radio, called a session passkey. Most methods return `AdminSnapshot`, an object containing the current device settings.

### `settings.snapshot`

Parameters: `{}`.

Result: `AdminSnapshot` for the local Host node.

### `settings.saveOwner`

Parameters:

| Field | Type |
|-------|------|
| `longName` | string |
| `shortName` | string |
| `isLicensed` | boolean |

Result: `AdminSnapshot`.

### `settings.saveConfigChanges`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `configs` | string[] | Base64 `ConfigProtos.Config`. |
| `moduleConfigs` | string[] | Base64 `ModuleConfigProtos.ModuleConfig`. |
| `channels` | string[] | Base64 `ChannelProtos.Channel`. |

Result: `AdminSnapshot`.

### `settings.setFixedPosition`

Parameters:

| Field | Type |
|-------|------|
| `latDegrees` | number |
| `lonDegrees` | number |
| `altMeters` | number |

Result: `AdminSnapshot`.

### `settings.removeFixedPosition`

Parameters: `{}`.

Result: `AdminSnapshot`.

### `settings.setRingtone`

Parameters: `{"ringtone": string}`.

Result: `AdminSnapshot`.

### `settings.command`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `command` | string | `reboot`, `shutdown`, `syncTime`, `enterDfuMode`. |
| `delaySeconds` | number | For `reboot` and `shutdown`, default `0`. |
| `epochSeconds` | number | For `syncTime`. |

Result: `AdminSnapshot`.

## Remote Admin Methods

The `admin.*` methods send Meshtastic administration commands to another node through the server’s selected connection.

General requirements:

- the selected Host connection must have a `ProtocolHandler`;
- target is specified through `nodeId` or non-zero `nodeNum`;
- target cannot be the local Host node;
- target node must have a public key.

General target params:

| Field | Type | Required |
|-------|------|----------|
| `nodeId` | string | conditional |
| `nodeNum` | number | conditional |

### `admin.load`

Parameters: target params.

Result: `AdminSnapshot`.

### `admin.requestConfig`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Enum name `AdminProtos.AdminMessage.ConfigType`, except `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.requestModuleConfig`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `type` | string | Enum name `AdminProtos.AdminMessage.ModuleConfigType`, except `UNRECOGNIZED`. |

Result: `AdminSnapshot`.

### `admin.saveOwner`

Parameters: target params + `longName`, `shortName`, `isLicensed`.

Result: `{"ok": true}`.

### `admin.saveConfigChanges`

Parameters: target params + `configs`, `moduleConfigs`, `channels` as in `settings.saveConfigChanges`.

Result: `{"ok": true}`.

### `admin.setFixedPosition`

Parameters: target params + `latDegrees`, `lonDegrees`, `altMeters`.

Result: `{"ok": true}`.

### `admin.removeFixedPosition`

Parameters: target params.

Result: `{"ok": true}`.

### `admin.setRingtone`

Parameters: target params + `ringtone`.

Result: `{"ok": true}`.

### `admin.setCannedMessages`

Parameters: target params + `messages`.

Result: `{"ok": true}`.

### `admin.command`

Parameters:

| Field | Type | Description |
|-------|------|-------------|
| `nodeId`/`nodeNum` | string/number | Target. |
| `command` | string | See the list below. |
| `delaySeconds` | number | For `reboot`/`shutdown`. |
| `epochSeconds` | number | For `syncTime`. |
| `location` | string | For backup commands: enum `AdminProtos.AdminMessage.BackupLocation`, except `UNRECOGNIZED`. |
| `preserveFavorites` | boolean | For `resetNodeDb`. |

Commands:

- `reboot`
- `shutdown`
- `syncTime`
- `backupPreferences`
- `restorePreferences`
- `removeBackupPreferences`
- `factoryResetConfig`
- `factoryResetDevice`
- `resetNodeDb`
- `enterDfuMode`

Result: `{"ok": true}`.

### `admin.refreshConnectionStatus`

Parameters: target params.

Result: `AdminSnapshot` plus field:

| Field | Type | Description |
|-------|------|-------------|
| `adminMessage` | string | Base64 `AdminProtos.AdminMessage`. |

### `AdminSnapshot`

| Field | Type | Description |
|-------|------|-------------|
| `targetNodeNum` | number |
| `node` | `Node` |
| `owner` | string | Base64 `MeshProtos.User`. |
| `deviceMetadata` | string | Base64 `MeshProtos.DeviceMetadata`. |
| `configs` | string[] | Base64 `ConfigProtos.Config`. |
| `moduleConfigs` | string[] | Base64 `ModuleConfigProtos.ModuleConfig`. |
| `channels` | string[] | Base64 `ChannelProtos.Channel`. |
| `channelCatalogReady` | boolean |
| `ringtone` | string |
| `ringtoneLoaded` | boolean |
| `cannedMessages` | string |
| `cannedMessagesLoaded` | boolean |
| `uiConfig` | string | Base64 `DeviceUIProtos.DeviceUIConfig`. |
| `connectionStatus` | string | Base64 `ConnStatusProtos.DeviceConnectionStatus`. |
| `queryStatuses` | `QueryStatus[]` |

`QueryStatus`:

| Field | Type | Description |
|-------|------|-------------|
| `key` | string |
| `state` | string | `SENT`, `RECEIVED`, `FAILED`. |
| `detail` | string |

## Host Events

### `message.incoming`

Sent when a message arrives. The client uses it to display a desktop notification and mark the message as unread.

Event data (`payload`):

| Field | Type |
|-------|------|
| `chatType` | string |
| `chatKey` | string |
| `message` | `Message` |
| `title` | string |
| `body` | string |

### `message.changed`

Sent when chat data changes without a new-message notification, for example when a reaction is added or message details change.

Event data (`payload`):

| Field | Type |
|-------|------|
| `chatType` | string |
| `chatKey` | string |
| `targetPacketId` | number |
| `message` | `Message`, optional |

### `message.status`

Sent when the delivery status of an outgoing message changes.

Event data (`payload`):

| Field | Type |
|-------|------|
| `chatType` | string |
| `chatKey` | string |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `message` | `Message` |

### `node.traceroute.result`

Event data (`payload`):

| Field | Type |
|-------|------|
| `requestId` | string |
| `status` | string, currently `ok` |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `responseFromNodeNum` | number |
| `responseFromNodeId` | string |
| `routeData` | string, Base64 `MeshProtos.RouteDiscovery` |
| `formattedText` | string |
| `timestamp` | number, Unix epoch seconds |
| `nodeNames` | JSON object mapping unsigned node numbers written as strings to display names |

### `packet.monitor.logged`

Event data (`payload`): `{"entry": PacketLogEntry}`.

### `packet.monitor.capture`

Event data (`payload`): `{"enabled": boolean}`.

### `packet.monitor.cleared`

Event data (`payload`): `{}`.

### `lua.runtime.event`

Event data (`payload`):

| Field | Type |
|-------|------|
| `type` | string: `INFO`, `OUTPUT`, `WARNING`, `ERROR`, `STARTED`, `STOPPED`, `UI_BOT_NOTICE`, `DEBUG_PAUSED`, `DEBUG_RESUMED` |
| `scriptId` | number |
| `message` | string |
| `errorMessage` | string, optional |
| `payload` | object, optional; for `UI_BOT_NOTICE` it is `LuaUiBotNotice` |

`LuaUiBotNotice`: `scriptId`, `source`, `name`, `chatType`, `chatKey`, `text`.

### `lua.ui.nodePick.request`

Event data (`payload`):

| Field | Type |
|-------|------|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `prompt` | string |
| `query` | string |
| `chatType` | string |
| `chatKey` | string |

The Remote client must answer with the `lua.ui.nodeSelection` method.

### `lua.form.command`

Event data (`payload`):

| Field | Type |
|-------|------|
| `scriptId` | number |
| `command` | string: `show`, `title`, `clear`, `add`, `update`, `remove`, `value` |
| `requestId` | string |
| `componentId` | string |
| `title` | string |
| `script` | `LuaScript` |
| `spec` | `LuaFormComponentSpec`, optional |

For `command="value"`, the client must return the field value through `lua.form.valueResult` with the same `requestId`. The current implementation checks remote form availability through the direct RPC server’s state, so extension form availability depends on which server connection is active.

## Examples

### Request/response

Request contents before encryption:

```json
{
  "type": "rpc_request",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "method": "system.ping",
  "params": {}
}
```

Response:

```json
{
  "type": "rpc_response",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "ok": true,
  "result": {
    "app": "MeshApp",
    "version": "1.0.0",
    "versionCode": 1,
    "remoteRpc": true,
    "activeConnections": 1
  }
}
```

### Unknown method

```json
{
  "type": "rpc_response",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "ok": false,
  "error": {
    "code": "METHOD_NOT_FOUND",
    "message": "RPC method not found: missing.method"
  }
}
```

### Chat send

Request params:

```json
{
  "chatType": "channel",
  "chatKey": "0",
  "text": "hello",
  "replyId": 0,
  "clientRequestId": "client-generated-id-1"
}
```

Result structure:

```json
{
  "message": {
    "fromNodeId": "!11111111",
    "toNodeId": "!ffffffff",
    "channelIndex": 0,
    "text": "hello",
    "timestamp": 1700000000,
    "outgoing": true,
    "status": "SENDING",
    "packetId": 123,
    "dbId": 456,
    "reactions": []
  },
  "chat": {
    "ownerNodeId": "!11111111",
    "connectionId": "connection-id",
    "items": []
  }
}
```

### Direct encrypted line

After `auth_ok`, TCP carries an encrypted line rather than the original JSON:

```text
enc1_xs3B9XjglG8FjQg4N0rQLj2r8eOJlYkq7H...
```

## Compatibility

- New methods are added only through explicit registration in `RpcMethodRegistry`.
- `METHOD_NOT_FOUND` means the server does not support the requested method. Clients must handle this case.
- Clients must ignore unknown fields in response results and event data.
- When reading enum fields, allow for unknown values: a new server version may send a value an older client does not recognize.
- Protobuf messages are sent as binary data encoded in Base64, not as the JSON representation of Protobuf.
- A Remote RPC profile must use `ProtocolType.REMOTE_RPC`; `ConnectionManager` rejects any other protocol for `ConnectionType.REMOTE_RPC`.
