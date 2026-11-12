# MeshApp-Remote-RPC-Protokoll

**Sprache:** [Русский](rpc-protocol.ru.md) | [English](rpc-protocol.md) | Deutsch

Mit Remote RPC lässt sich eine MeshApp-Anwendung von einer anderen aus bedienen. Beispielsweise kann MeshApp auf einem entfernten Computer den Auftrag erhalten, über sein angeschlossenes Funkgerät eine Nachricht zu senden. Die Anwendung, die den Auftrag ausführt, heißt hier **Server** oder **Host**. Die Anwendung, die den Auftrag sendet, heißt **Client**.

RPC steht für „Remote Procedure Call“, also den Aufruf einer Funktion auf einem anderen Rechner. Der Client nennt die gewünschte Funktion und übergibt ihre Parameter. MeshApp schreibt Anfragen und Antworten als JSON, ein Textformat mit benannten Feldern. Es handelt sich um ein eigenes Protokoll, nicht um JSON-RPC 2.0. Es überträgt Befehle und Ergebnisse der Anwendung und bietet keinen direkten Zugriff auf den Datenstrom eines Meshtastic- oder MeshCore-Funkgeräts.

Zum Einrichten einer Verbindung genügt die [Anleitung zum Serverstart](command-line.de.md#rpc-server-starten). Die folgenden Abschnitte beschreiben das Protokoll für die Entwicklung eigener Clients und Integrationen.

Wichtige Quelldateien:

- `src/main/java/com/meshtastic/client/rpc/*` — Verbindung, Schlüsselprüfung, Nachrichtenformate und Verarbeitung der Anfragen.
- `src/main/java/com/meshtastic/client/service/RemoteRpcHostService.java` — verfügbare Servermethoden.
- `src/main/java/com/meshtastic/client/protocol/rpc/*` — Umwandlung der Anwendungsdaten in JSON und zurück.
- `src/main/java/com/meshtastic/client/connection/rpc/*` — RPC-Verbindungen in `ConnectionManager`.

## Begriffe

| Begriff | Bedeutung |
|---------|-----------|
| Server (Host) | Die MeshApp-Anwendung, die Befehle ausführt. Ihr RPC-Server ist eingeschaltet oder sie ist mit einem External RPC Router verbunden. |
| Client | Die MeshApp-Anwendung, die Befehle an den Server sendet. Im Code heißt dieser Verbindungstyp `ConnectionType.REMOTE_RPC` / `ProtocolType.REMOTE_RPC`. |
| Direkte Verbindung (Direct) | TCP-Verbindung vom Client zum Server. Die Standardadresse des Servers ist `127.0.0.1:44030`. |
| Verbindung über einen Router | Client und Server verbinden sich per WebSocket mit einem External RPC Router. Dieser leitet Nachrichten weiter. Den Zugangsschlüssel prüfen Client und Server selbst; nur sie können die RPC-Daten entschlüsseln. |
| RPC-Nachricht (Envelope) | Ein JSON-Objekt mit einer Anfrage, einer Antwort oder einem Ereignis. Ein Ereignis informiert den Client beispielsweise über eine neue Chatnachricht. |
| Übertragungsblock (Frame) | Eine Zeile der direkten TCP-Verbindung oder der Inhalt von `payload.frame` bei einer Router-Verbindung. Nach der Schlüsselprüfung enthält der Block eine verschlüsselte RPC-Nachricht. |
| Raum (Room) | Eine Gruppe von Verbindungen auf dem Router. Anhand der gemeinsamen Kennung `roomId` ordnet der Router Client und Server einander zu. |

## Verbindungsmodi

<a id="direct-tcp"></a>

### Direkte TCP-Verbindung

Aktivieren Sie den Server in den Anwendungseinstellungen unter **RPC**.

Sie können ihn auch ohne Anwendungsfenster starten: mit `--rpc-server` oder, aus dem Quellcode, mit `./gradlew runRpcServer`. Die Einrichtung und die verfügbaren Optionen sind unter [RPC-Server starten](command-line.de.md#rpc-server-starten) beschrieben.

Standardparameter:

| Parameter | Wert |
|-----------|------|
| Adresse für eingehende Verbindungen | `127.0.0.1` |
| Port | `44030` |
| Zugangsschlüssel | Zeichenfolge `mra1_...`. Sie lässt sich in den Einstellungen erzeugen. Beim Start über die Kommandozeile erzeugt MeshApp einen Schlüssel, falls noch keiner vorhanden ist. |

Der direkte Server bedient jeweils einen Client, der die Schlüsselprüfung bestanden hat. Solange dieser Client verbunden ist, werden neue TCP-Verbindungen geschlossen.

Zu Beginn prüfen Client und Server, ob beide denselben Zugangsschlüssel kennen. Diese Prüfung heißt Authentifizierung. Dafür tauschen sie unverschlüsselte JSON-Nachrichten aus. Jede Nachricht wird in UTF-8 codiert und in einer eigenen Zeile übertragen.

Nach erfolgreicher Prüfung werden die RPC-Nachrichten verschlüsselt. Jede TCP-Zeile enthält dann einen Block der Form `enc1_<base64url>`. Das Präfix `enc1_` kennzeichnet verschlüsselte Daten; base64url stellt die Binärdaten als Text dar.

<a id="external-rpc-router"></a>

### Verbindung über einen External RPC Router

Ein External RPC Router vermittelt zwischen Client und Server, wenn keine direkte TCP-Verbindung möglich ist. Standardmäßig wird der Cloud-Router verwendet:

| Parameter | Wert |
|-----------|------|
| Name in der Oberfläche | `cloud.meshapp.privatepractice.app` |
| Adresse | `wss://cloud.meshapp.privatepractice.app` |
| Port | `443` |
| Standardpfad | `/rpc` |

Die Verbindungsadresse zum Router hat folgenden Aufbau:

```text
<ws-or-wss>://<host>:<port>/<path>?roomId=<roomId>&role=<host-or-client>
```

Dabei gelten folgende Regeln:

- Fehlt die Protokollangabe, wird `ws://` ergänzt.
- Erlaubt sind `ws://` und `wss://`.
- Der Standardport ist `8080` für `ws` und `443` für `wss`.
- Der Standardpfad ist `/rpc`.
- `roomId` wird aus dem Zugangsschlüssel berechnet, ohne den Schlüssel selbst offenzulegen.
- `role` enthält `host` für den Server und `client` für den Client.

Beispiel:

```text
ws://router.example.org:8080/rpc?roomId=erpc1_MpmfGysDJIvccpcIQYIfh0aeET-OORKNPAXG-UoAyK0&role=client
```

Der Router empfängt und sendet folgende JSON-Nachrichten über WebSocket:

| Richtung | JSON | Zweck |
|----------|------|-------|
| Server → Router | `{"type":"host_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Einen Datenblock an den angegebenen Client weiterleiten. |
| Client → Router | `{"payload":{"frame":"..."}}` | Einen Datenblock an den Server im selben Raum weiterleiten. |
| Client oder Server → Router | `{"type":"router_ping"}` | Prüfen, ob die Verbindung noch besteht. |
| Router → Server | `{"type":"host_ready"}` | Der Server ist im Raum registriert. |
| Router → Server | `{"type":"client_joined","clientSessionId":"..."}` | Ein neuer Client hat sich mit dem Raum verbunden. |
| Router → Server | `{"type":"client_frame","clientSessionId":"...","payload":{"frame":"..."}}` | Einen Datenblock vom Client zustellen. |
| Router → Server | `{"type":"client_disconnected","clientSessionId":"..."}` | Der Client hat die Verbindung getrennt. |
| Router → Client | `{"type":"client_ready"}` | Der Client ist im Raum registriert. |
| Router → Client | `{"type":"host_connected"}` | Der Server ist erreichbar. |
| Router → Client | `{"type":"host_frame","payload":{"frame":"..."}}` oder `{"type":"broadcast","payload":{"frame":"..."}}` | Einen Datenblock vom Server zustellen. |
| Router → Client | `{"type":"host_disconnected"}` | Der Server hat die Verbindung getrennt. |
| Router → Client oder Server | `{"type":"router_pong"}` | Antwort auf die Verbindungsprüfung. |
| Router → Client oder Server | `{"type":"router_error","message":"..."}` | Fehler der Router-Verbindung melden. |

Client und Server senden alle 15 Sekunden `router_ping`. Treffen länger als 45 Sekunden weder `router_pong` noch andere Nachrichten vom Router ein, gilt die Verbindung als unterbrochen und wird geschlossen.

<a id="access-key"></a>

## Zugangsschlüssel

Auf Client und Server wird derselbe geheime Zugangsschlüssel hinterlegt. Beim Verbindungsaufbau weisen beide Seiten nach, dass sie ihn kennen, ohne ihn über das Netzwerk zu senden.

Format:

```text
mra1_<base64url-without-padding-32-random-bytes>
```

Eigenschaften:

- Der Schlüssel besteht aus 32 zufälligen Bytes.
- Für jede Prüfung werden einmalige Zufallswerte (`nonce`) mit jeweils 24 Bytes erzeugt.
- Schlüssel und Zufallswerte werden als base64url ohne abschließende `=`-Zeichen geschrieben. Der Zugangsschlüssel selbst wird nie über das Netzwerk gesendet.
- Die Nachweise (`proof`) und die Schlüssel zur Verschlüsselung der Verbindung werden mit Berechnungen auf Basis von HMAC-SHA256 erzeugt.
- Die Raumkennung besteht aus `erpc1_` und einem HMAC-SHA256-Ergebnis in base64url ohne abschließende `=`-Zeichen.

Berechnung der Raumkennung:

```text
roomId = "erpc1_" + base64url_no_padding(HMAC_SHA256(keyBytes, "ERPC-Router room id v1"))
```

<a id="authentifizierung-und-verschluesselung"></a>

## Authentifizierung und Verschlüsselung

Direkte Verbindungen und Router-Verbindungen verwenden dieselbe Version 2 des gesicherten RPC-Protokolls. Bei Router-Verbindungen werden die Nachrichten zur Schlüsselprüfung in `payload.frame` übertragen. Client und Server prüfen den Schlüssel und verschlüsseln die Daten selbst; der Router erhält den Zugangsschlüssel nicht.

Die Prüfung erfolgt in drei Schritten: Der Server sendet einen Zufallswert, der Client berechnet daraus mit dem gemeinsamen Schlüssel einen Nachweis, und der Server bestätigt anschließend ebenfalls seine Kenntnis des Schlüssels. Die folgenden Abschnitte zeigen die genauen Nachrichtenformate und Berechnungen.

<a id="schritt-1-host-challenge"></a>

### Schritt 1: Der Server beginnt die Prüfung

Der Server erzeugt den Zufallswert `serverNonce` und sendet diese unverschlüsselte Nachricht:

```json
{
  "type": "auth_challenge",
  "version": 2,
  "cipher": "AES-256-GCM",
  "nonce": "serverNonce"
}
```

<a id="schritt-2-client-response"></a>

### Schritt 2: Der Client weist seine Kenntnis des Schlüssels nach

Der Client erzeugt einen eigenen Zufallswert `clientNonce`. Aus dem gemeinsamen Schlüssel und beiden Zufallswerten berechnet er den Nachweis `proof` und sendet:

```json
{
  "type": "auth_response",
  "version": 2,
  "clientNonce": "clientNonce",
  "proof": "base64urlClientProof"
}
```

Berechnung des Client-Nachweises:

```text
clientProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-client-auth-v2", serverNonce, clientNonce))
)
```

<a id="schritt-3-host-okerror"></a>

### Schritt 3: Der Server prüft die Antwort

Stimmt der Nachweis nicht mit dem erwarteten Wert überein, meldet der Server einen Fehler:

```json
{
  "type": "auth_error",
  "message": "invalid access key"
}
```

Ist der Nachweis korrekt, antwortet der Server mit `auth_ok` und seinem eigenen Nachweis:

```json
{
  "type": "auth_ok",
  "version": 2,
  "cipher": "AES-256-GCM",
  "proof": "base64urlServerProof"
}
```

Berechnung des Server-Nachweises:

```text
serverProof = base64url_no_padding(
  HMAC_SHA256(keyBytes, transcript("meshapp-rpc-server-auth-v2", serverNonce, clientNonce))
)
```
`transcript(context, serverNonce, clientNonce)` ist die Bytefolge, aus der der Nachweis berechnet wird. Sie enthält den Kontext sowie Länge und Inhalt beider Zufallswerte. `utf8` steht für die UTF-8-Codierung einer Zeichenfolge. `uint32_be` ist eine Ganzzahl aus vier Bytes, bei der das höchstwertige Byte zuerst steht:
`transcript(context, serverNonce, clientNonce)` wird so codiert:

```text
utf8(context)
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

Der Client prüft `serverProof`. Nach erfolgreicher Prüfung berechnen beide Seiten unabhängig voneinander dieselben Schlüssel für diese Verbindung.

<a id="session-key-ableitung"></a>

### Schlüssel für die Verschlüsselung berechnen

Zunächst wird aus beiden Zufallswerten ein zusätzlicher Eingabewert, das Salt (`saltTranscript`), gebildet:

```text
utf8("meshapp-rpc-secure-v1:salt")
uint32_be(len(utf8(serverNonce)))
utf8(serverNonce)
uint32_be(len(utf8(clientNonce)))
utf8(clientNonce)
```

Danach werden die Schlüssel nach einem HKDF-ähnlichen Verfahren abgeleitet. HKDF erzeugt Schlüssel aus einem gemeinsamen Geheimnis. Für jede Übertragungsrichtung werden ein eigener Schlüssel und ein eigenes Präfix für den einmaligen Verschlüsselungsparameter berechnet:

```text
prk = HMAC_SHA256(saltTranscript, keyBytes)
clientToServerKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:key", 32)
clientToServerNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:client-to-server:nonce-prefix", 4)
serverToClientKey = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:key", 32)
serverToClientNoncePrefix = HKDF-Expand(prk, "meshapp-rpc-secure-v1:server-to-client:nonce-prefix", 4)
```
`HKDF-Expand` berechnet nacheinander HMAC-SHA256-Blöcke und fügt sie bis zur gewünschten Länge zusammen. `||` bedeutet in den Formeln das Aneinanderfügen von Bytefolgen:
`HKDF-Expand` verwendet HMAC-SHA256-Blöcke:

```text
T(0) = empty
T(n) = HMAC_SHA256(prk, T(n-1) || info || uint8(n))
output = T(1) || T(2) || ...
```

<a id="verschluesselte-frames"></a>

### Verschlüsselte Nachrichten übertragen

Jede RPC-Nachricht wird einzeln verschlüsselt. Dafür gelten folgende Parameter:

| Parameter | Wert |
|-----------|------|
| Algorithmus | `AES/GCM/NoPadding` |
| Schlüssel | 32 Bytes, ein eigener Schlüssel je Übertragungsrichtung |
| Einmaliger Verschlüsselungsparameter (`nonce`) | 4 Bytes Richtungspräfix und 8 Bytes Nachrichtenzähler, höchstwertiges Byte zuerst |
| Nachrichtenzähler (`sequence`) | Getrennt für jede Richtung, beginnt bei `0` |
| Zusätzlich geprüfte Daten (AAD) | `meshapp-rpc-secure-v1:frame` |
| Prüfwert für die Integrität (GCM-Tag) | 128 Bit |
| Blockpräfix | `enc1_` |
| Darstellung der Binärdaten | base64url ohne abschließende `=`-Zeichen |

Der übertragene Block enthält die verschlüsselten Daten und den Prüfwert:

```text
enc1_<base64url_no_padding(ciphertext_plus_gcm_tag)>
```

Beginnt ein Block nicht mit `enc1_`, ist der Zähler erschöpft oder schlägt die GCM-Integritätsprüfung fehl, wird die Verbindung mit einem Fehler geschlossen.

<a id="rpc-envelope"></a>

## Aufbau der RPC-Nachrichten

Eine RPC-Nachricht ist eine Anfrage, eine Antwort oder ein Ereignis in Form eines JSON-Objekts. MeshApp verwendet Gson mit der Einstellung `serializeNulls`; deshalb können Felder ausdrücklich den Wert `null` enthalten. Vor der Verschlüsselung wird das JSON ohne Einrückungen und Zeilenumbrüche geschrieben.

Bei einer direkten Verbindung wird jeder verschlüsselte Block in einer eigenen Zeile gesendet. Bei einer Router-Verbindung steht derselbe Block im Feld `payload.frame`. Die JSON-Beispiele unten zeigen den Inhalt vor der Verschlüsselung. Einrückungen und Zeilenumbrüche dienen dort nur der Lesbarkeit.

<a id="request"></a>

### Anfrage

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

Regeln:

- `type` muss `rpc_request` enthalten.
- `requestId` ist erforderlich und darf weder leer sein noch nur aus Leerzeichen bestehen.
- Der Client muss `method` angeben. Leerzeichen am Anfang und Ende werden vor dem Senden entfernt.
- `params` muss ein JSON-Objekt sein. Fehlt das Feld oder enthält es einen anderen Typ, verwendet der Server `{}`.
- Der Server führt nur Methoden aus, die ausdrücklich in `RpcMethodRegistry` registriert sind.
- Ungültiges JSON, Nachrichten ohne JSON-Objekt, unbekannte Werte für `type` und Anfragen ohne `requestId` werden ohne Antwort verworfen.

<a id="erfolgreiche-response"></a>

### Erfolgreiche Antwort

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

`result` kann ein Objekt, ein Array, eine Zeichenfolge, eine Zahl, einen Wahrheitswert oder `null` enthalten. Die meisten Methoden geben ein Objekt zurück. Über `requestId` wird die Antwort der ursprünglichen Anfrage zugeordnet.

<a id="error-response"></a>

### Fehlerantwort

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

Fehlercodes:

| Code | Bedeutung |
|------|------------------|
| `BAD_REQUEST` | Bei der Verarbeitung wurde `IllegalArgumentException` ausgelöst. Meist fehlt ein Parameter oder enthält einen ungültigen Wert. |
| `METHOD_NOT_FOUND` | Die angefragte Methode ist auf dem Server nicht registriert. |
| `CONNECTION_FAILED` | Bei der Verarbeitung wurde `ConnectionException` ausgelöst. Die ursprüngliche Fehlerursache wird, sofern bekannt, in `message` ergänzt. |
| `INTERNAL_ERROR` | Unerwarteter Verarbeitungsfehler oder `RpcException` ohne eigenen Fehlercode. |
| `TIMEOUT` | Der Client hat innerhalb der vorgesehenen Zeit keine Antwort erhalten. |
| `TRANSPORT_CLOSED` | Der RPC-Client oder die Verbindung wurde vor oder während des Aufrufs geschlossen. |

Eine Servermethode kann über `RpcException` einen eigenen Fehlercode zurückgeben. Leerzeichen am Anfang und Ende werden entfernt. Ein leerer Code wird durch `INTERNAL_ERROR` ersetzt.

<a id="event"></a>

### Ereignis

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

Der Server sendet Ereignisse von sich aus, etwa wenn eine Chatnachricht eintrifft. Ereignisse haben keine `requestId` und erwarten keine Antwort. Fehlt `payload`, erhalten die Ereignisempfänger auf dem Client den JSON-Wert `null`. Ein Ereignis ohne nichtleeren Namen im Feld `event` wird ignoriert.

<a id="timeouts"></a>

## Wartezeiten

| Vorgang | Zeit |
|---------|------|
| Standardwartezeit auf eine RPC-Antwort | 30 Sekunden |
| Prüfung mit `system.ping` beim Verbindungsaufbau | 5 Sekunden |
| RPC-Aufrufe aus Formularen der Anwendung | Meist 15 Sekunden |
| Aufbau einer direkten TCP-Verbindung | 5 Sekunden |
| Verbindung und Schlüsselprüfung über den Router | 8 Sekunden |
| Abstand zwischen Verbindungsprüfungen zum Router | 15 Sekunden |
| Zulässige Zeit ohne Nachrichten vom Router | 45 Sekunden |
| Warten auf einen Feldwert aus einem entfernten Lua-Formular | 2 Sekunden |
| Aufbewahrung einer noch unbeantworteten Routenabfrage | 365 Sekunden |

## Allgemeine JSON-Regeln

- Erforderliche Textfelder müssen JSON-Zeichenfolgen enthalten. Nach dem Entfernen äußerer Leerzeichen dürfen sie nicht leer sein.
- Zahlen werden als Java-Typ `int`, `long`, `double` oder `float` gelesen. Bereichsprüfungen erfolgen nur dort, wo sie ausdrücklich angegeben sind.
- Logische Felder akzeptieren nur die JSON-Werte `true` oder `false`.
- Fehlt ein optionales Textfeld, wird meist `""` verwendet.
- Fehlt ein optionales Zahlenfeld, wird meist `0` verwendet.
- Fehlt ein optionales logisches Feld, wird meist `false` verwendet.
- Eine Knotenkennung `nodeId` besteht normalerweise aus `!` und 8 Hexadezimalzeichen.
- `nodeNum` wird als vorzeichenbehaftete Java-Zahl vom Typ `int` übertragen. Für die Darstellung ohne Vorzeichen wird `nodeId` verwendet.
- Zeitangaben in Chatnachrichten und Telemetrie zählen Sekunden seit dem 1. Januar 1970, 00:00:00 UTC (Unix-Zeit).
- `capturedAt` im Paketprotokoll zählt Millisekunden seit demselben Zeitpunkt.
- Binäre Protobuf-Daten sowie `publicKey`, `routeData` und `adminMessage` werden als normales Base64 übertragen, nicht als base64url.

<a id="host-state-model"></a>

## Daten und Verbindungen des Servers

Der Client ruft Methoden auf dem Server auf. Diese arbeiten mit den Daten und Verbindungen des Servers:

- `connection.*` verwaltet die auf dem Server gespeicherten Verbindungen.
- Die meisten Methoden unter `chat.*`, `node.*`, `telemetry.*`, `settings.*` und `admin.*` benötigen eine ausgewählte, aktive Geräteverbindung auf dem Server.
- Beim Aufbau der RPC-Verbindung ruft der Client `system.ping` auf. Danach speichert `RemoteRpcState` den `RpcClient` und das Ergebnis dieser Prüfung.
- Beliebige Funkdaten lassen sich nicht über `TransportConnection.sendBytes` versenden. RPC unterstützt nur die dafür vorgesehenen Methoden.

## Methoden

**Parameter** bezeichnet im Folgenden den Inhalt von `params` in einer Anfrage, **Ergebnis** den Inhalt von `result` in der Antwort. Werden keine Parameter benötigt, wird `{}` übergeben.

Verweist das Ergebnis auf eine andere Methode, statt Felder aufzulisten, geben beide Methoden Daten mit demselben Aufbau zurück.

In den Tabellen bedeutet `string` Zeichenfolge, `number` Zahl, `boolean` den Wert `true` oder `false`, `object` JSON-Objekt und `null` einen fehlenden Wert. `string[]` oder `Node[]` bezeichnet ein Array des jeweiligen Typs. Feldnamen und Werte in Backticks müssen genau wie angegeben übertragen werden.

### `system.ping`

Prüft, ob der RPC-Server antwortet.

Parameter:

```json
{}
```

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `app` | string | Immer `MeshApp`. |
| `version` | string | Anwendungsversion. |
| `versionCode` | number | Numerischer Versionscode der Anwendung. |
| `remoteRpc` | boolean | Immer `true`. |
| `activeConnections` | number | Anzahl der aktiven Verbindungen auf dem Server. |

### `connection.list`

Gibt die auf dem Server gespeicherten Verbindungen zurück.

Parameter: `{}`.

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `selectedConnectionId` | string/null | Kennung der auf dem Server ausgewählten Verbindung. |
| `items` | `Connection[]` | Gespeicherte Verbindungen. |

`Connection`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `id` | string | Dauerhafte Kennung des Verbindungsprofils. |
| `name` | string | Profilname. |
| `type` | string | `TCP`, `SERIAL`, `BLE`, `REMOTE_RPC`. |
| `protocol` | string | `MESHTASTIC`, `MESHCORE_KISS`, `MESHCORE_COMPANION`, `REMOTE_RPC`. |
| `connected` | boolean | Ob aktuell verbunden. |
| `reconnecting` | boolean | Wird die Verbindung gerade erneut aufgebaut? |
| `selected` | boolean | Ist diese Verbindung auf dem Server ausgewählt? |
| `nodeId` | string/null | Knotenkennung, soweit bekannt. |
| `address` | string | Verbindungsadresse als Text für die Anzeige. |

### `connection.connect`

Stellt die Verbindung mit dem gespeicherten Profil her und wählt sie auf dem Server aus.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `id` | string | ja | Kennung des Verbindungsprofils. |

Ergebnis: dieselben Felder wie bei `connection.list`, zusätzlich `connection`, sofern das Profil gefunden wurde.

### `connection.disconnect`

Trennt die Verbindung des angegebenen Profils auf dem Server.

Parameter: `{"id":"..."}`.

Ergebnis: gleicher Aufbau wie bei `connection.connect`.

### `connection.select`

Wählt ein Profil auf dem Server aus, ohne die Verbindung herzustellen.

Parameter: `{"id":"..."}`.

Ergebnis: gleicher Aufbau wie bei `connection.connect`.

<a id="chat-methoden"></a>

## Chats und Nachrichten

Für die Chatmethoden gelten folgende Bezeichnungen:

- `chatType`: `channel` oder `dm`;
- `chatKey`: bei `channel` der Kanalindex als Text, etwa `"0"`; bei `dm` die `nodeId` des Gesprächspartners, etwa `"!12345678"`;
- `ownerNodeId`: Kennung des Funkgeräts, mit dem der Server über das ausgewählte Profil verbunden ist.

### `chat.list`

Gibt die Liste der Kanäle und direkten Dialoge zurück.

Parameter: `{}`.

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Kennung des Funkgeräts in der ausgewählten Serververbindung. |
| `connectionId` | string | Kennung der ausgewählten Serververbindung. |
| `items` | `ChatItem[]` | Chats. |

`ChatItem`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `type` | string | `CHANNEL` oder `DIRECT_MESSAGE`. |
| `displayName` | string | Chatname. |
| `avatarText` | string | Avatartext. |
| `avatarColor` | string | Avatarfarbe, z. B. `#5B8DEF`. |
| `lastMessageText` | string | Text der letzten Nachricht. |
| `lastMessageTime` | number | Zeitpunkt der letzten Nachricht in Unix-Sekunden. |
| `unreadCount` | number | Anzahl ungelesener Nachrichten. |
| `channelIndex` | number | Kanalindex oder `0`. |
| `peerNodeId` | string | Knotenkennung des Gesprächspartners bei Direktnachrichten, sonst `""`. |
| `muted` | boolean | Sind Benachrichtigungen für diesen Chat ausgeschaltet? |

### `chat.messages`

Lädt Chat-Nachrichten.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Kanalindex als Text oder Knotenkennung des Gesprächspartners. |
| `limit` | number | nein | Anzahl der Nachrichten von 1 bis 200, Standard `50`. |
| `beforeDbId` | number | nein | Bei einem Wert größer als 0 werden Nachrichten mit kleinerer Datenbank-ID geladen. |
| `afterDbId` | number | nein | Bei einem Wert größer als 0 und `beforeDbId == 0` werden Nachrichten mit größerer Datenbank-ID geladen. |

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Kennung des am Server angeschlossenen Funkgeräts. |
| `items` | `Message[]` | Nachrichten. |

### `chat.markRead`

Markiert einen Chat als gelesen.

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `chatType` | string | ja |
| `chatKey` | string | ja |

Ergebnis: `chat.list`.

### `chat.send`

Sendet eine Nachricht.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Kanalindex als Text oder Knotenkennung des Gesprächspartners. |
| `text` | string | ja | Nicht-leerer Text. |
| `replyId` | number | nein | Paketkennung der Nachricht, auf die geantwortet wird. Kann negativ sein. |
| `clientRequestId` | string | nein | Kennung zum Vermeiden doppelter Sendungen. Bei einer Wiederholung mit derselben Kennung innerhalb von 5 Minuten wird die bereits erzeugte Nachricht zurückgegeben. |

Ergebnis:

| Feld | Typ |
|------|-----|
| `message` | `Message` |
| `chat` | Ergebnis von `chat.list` |

Der Server speichert dafür bis zu 512 Einträge, jeweils für 5 Minuten.

### `chat.retry`

Sendet eine ausgehende Nachricht erneut, deren vorheriger Sendeversuch fehlgeschlagen ist.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `chatType` | string | ja | `channel` oder `dm`. |
| `chatKey` | string | ja | Kennung des Chats. |
| `dbId` | number | bedingt | Datenbank-ID der Nachricht. Erforderlich ist `dbId > 0` oder `packetId != 0`. |
| `packetId` | number | bedingt | Paketkennung der Nachricht. |

Ergebnis: wie `chat.send`.

Erneutes Senden ist nur für ausgehende Nachrichten mit Sendefehler möglich. MeshCore Companion unterstützt diese Methode nicht.

### `chat.react`

Sendet eine Reaktion auf eine Nachricht.

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `chatType` | string | ja |
| `chatKey` | string | ja |
| `targetPacketId` | number | ja, nicht `0` |
| `emoji` | string | ja |

Ergebnis:

| Feld | Typ |
|------|-----|
| `message` | Aktualisiertes `Message`-Objekt mit der Reaktion |
| `chat` | Ergebnis von `chat.list` |

Reaktionen werden von MeshCore Companion nicht unterstützt.

### `Message`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `fromNodeId` | string | Knotenkennung des Absenders. |
| `toNodeId` | string | Knotenkennung des Empfängers; bei einer Nachricht an alle Knoten normalerweise `!ffffffff`. |
| `channelIndex` | number | Kanalindex. |
| `text` | string | Text. |
| `timestamp` | number | Zeitpunkt der Nachricht in Unix-Sekunden. |
| `outgoing` | boolean | Nachricht wurde vom lokalen Gerät gesendet. |
| `status` | string/null | `SENDING`, `DELIVERED`, `CONFIRMED`, `FAILED`. |
| `packetId` | number | Paketkennung von Meshtastic. |
| `errorReason` | string/null | Grund für Sendefehler. |
| `replyId` | number | Paketkennung der Nachricht, auf die geantwortet wird. |
| `replyText` | string/null | Text der ursprünglichen Nachricht. |
| `replyToOutgoing` | boolean | War die ursprüngliche Nachricht ausgehend? |
| `hopStart` | number | Ursprünglich erlaubte Anzahl von Weiterleitungen über andere Knoten. |
| `hopLimit` | number | Noch erlaubte Anzahl von Weiterleitungen über andere Knoten. |
| `rxRssi` | number | RSSI: Stärke des empfangenen Funksignals. |
| `rxSnr` | number | SNR: Verhältnis von Signal- zu Rauschleistung. |
| `senderName` | string | Name des Absenders für die Anzeige. |
| `viaMqtt` | boolean | Nachricht kam über MQTT. |
| `systemMessage` | boolean | Systemnachricht. |
| `dbId` | number | Kennung der Nachricht in der lokalen Datenbank. |
| `reactions` | `Reaction[]` | Reaktionen. |

`Reaction`:

| Feld | Typ |
|------|-----|
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

<a id="node-methoden"></a>

## Netzknoten

### `node.list`

Gibt die Knoten der ausgewählten Serververbindung zurück.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `includeFavorites` | boolean | nein | Gespeicherte Favoriten auch dann einschließen, wenn sie derzeit nicht erreichbar sind. |
| `includeIgnored` | boolean | nein | Gespeicherte ignorierte Knoten auch dann einschließen, wenn sie derzeit nicht erreichbar sind. |

Ergebnis:

| Feld | Typ |
|------|-----|
| `ownerNodeId` | string |
| `items` | `Node[]` |

### `node.get`

Gibt Informationen zu einem einzelnen Knoten zurück.

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | ja |

Ergebnis: `{"ownerNodeId":"...","items":[Node]}`.

### `node.traceroute`

Fragt die Route zu einem Knoten ab, also die Folge der Knoten, über die eine Nachricht dorthin gelangt. Das Ergebnis wird später als Ereignis `node.traceroute.result` gesendet.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | bedingt | Kennung des Zielknotens. |
| `nodeNum` | number | bedingt | Nummer des Zielknotens. Geben Sie `nodeId` oder eine von null verschiedene `nodeNum` an. |
| `requestId` | string | nein | Fehlt die Kennung, erzeugt der Server eine UUID als eindeutige Anfragekennung. |

Ergebnis:

| Feld | Typ |
|------|-----|
| `requestId` | string |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `nodeNames` | JSON-Objekt: vorzeichenlose Knotennummer als Text → angezeigter Name |

Die Methode steht nur zur Verfügung, wenn die Verarbeitung des gewählten Protokolls Routenabfragen unterstützt.

### `node.refresh`

Fordert aktuelle Benutzerdaten (`UserInfo`) vom Knoten an.

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeNum` | number | ja, nicht `0` |
| `includeFavorites` | boolean | nein |
| `includeIgnored` | boolean | nein |

Ergebnis: wie `node.list`, mit denselben Werten für `includeFavorites` und `includeIgnored`.

### `node.delete`

Entfernt den Knoten aus den Daten der aktuellen Verbindung und aus dem gespeicherten Cache.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | ja | Knotenkennung. |
| `nodeNum` | number | nein | Bei einer Nummer ungleich null wird der Knoten auch aus dem aktuellen `DeviceState` entfernt. |
| `includeFavorites` | boolean | nein | Wird beim Erstellen des Ergebnisses von `node.list` berücksichtigt. |
| `includeIgnored` | boolean | nein | Wird beim Erstellen des Ergebnisses von `node.list` berücksichtigt. |

Ergebnis: `node.list`.

### `node.favorite`

Fügt den Knoten den Favoriten hinzu oder entfernt ihn daraus.

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | ja |
| `enabled` | boolean | nein, Standard `false` |
| `includeFavorites` | boolean | nein |
| `includeIgnored` | boolean | nein |

Ergebnis: `node.list`.

### `node.ignored`

Schaltet das Ignorieren des Knotens ein oder aus.

Parameter: wie `node.favorite`.

Ergebnis: `node.list`.

### `Node`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeNum` | number | Knotennummer als Java-Zahl vom Typ `int`. |
| `nodeId` | string | Dauerhafte Kennung wie `!12345678`. |
| `longName` | string |
| `shortName` | string |
| `latitude` | number |
| `longitude` | number |
| `altitude` | number |
| `snr` | number |
| `lastHeard` | number | Zeitpunkt des letzten Empfangs in Unix-Sekunden oder ein von der Protokollverarbeitung gelieferter Wert. |
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
| `publicKey` | string | Öffentlicher Schlüssel in Base64 oder `""`. |
| `unmessagable` | boolean |
| `licensed` | boolean |
| `favorite` | boolean |
| `ignored` | boolean |

## Telemetry-Methoden

### `telemetry.dashboard`

Gibt die Daten für die Telemetrieansicht eines Knotens zurück.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `nodeId` | string | nein | Fehlt der Wert oder enthält er nur Leerzeichen, wird das am Server angeschlossene Funkgerät verwendet. |
| `sinceEpoch` | number | nein | Beginn des Zeitraums in Unix-Sekunden, Standard `0`. |
| `maxFutureTs` | number | nein | Ende des Zeitraums in Unix-Sekunden. Bei `<= 0` verwendet der Server die aktuelle Zeit plus 300 Sekunden. |

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `ownerNodeId` | string | Kennung des am Server angeschlossenen Funkgeräts. |
| `nodeId` | string | Kennung des Knotens, dessen Telemetrie geladen wurde. |
| `entries` | `TelemetryEntry[]` | Telemetrie des ausgewählten Knotens. |
| `qualityEntries` | `TelemetryEntry[]` | Daten zur Verbindungsqualität im Netz. |

Gson wandelt das Java-Objekt `TelemetryEntry` in JSON um. Die Felder sind hier nach ihrem Zweck gruppiert:

- Grunddaten: `timestamp`, `nodeId`, `telemetryVariant`;
- Gerätemesswerte: `batteryLevel`, `externallyPowered`, `voltage`, `channelUtilization`, `airUtilTx`, `deviceUptimeSeconds`;
- Umweltsensoren: `temperature`, `relativeHumidity`, `barometricPressure`, `gasResistance`, `environmentVoltage`, `environmentCurrent`, `iaq`, `distance`, `lux`, `whiteLux`, `irLux`, `uvLux`, `windDirection`, `windSpeed`, `weight`, `windGust`, `windLull`, `radiation`, `rainfall1h`, `rainfall24h`, `soilMoisture`, `soilTemperature`, `oneWireTemperatures`;
- Luftqualität: `pm10Standard`, `pm25Standard`, `pm100Standard`, `pm10Environmental`, `pm25Environmental`, `pm100Environmental`, `particles03um`, `particles05um`, `particles10um`, `particles25um`, `particles50um`, `particles100um`, `co2`, `co2Temperature`, `co2Humidity`, `formFormaldehyde`, `formHumidity`, `formTemperature`, `pm40Standard`, `particles40um`, `pmTemperature`, `pmHumidity`, `pmVocIdx`, `pmNoxIdx`, `particlesTps`;
- Stromversorgung: `ch1Voltage`..`ch8Voltage`, `ch1Current`..`ch8Current`;
- Gerätestatistik: `numPacketsRx`, `numPacketsRxBad`, `numRxDupe`, `numPacketsTx`, `numTxDropped`, `numTxRelay`, `numTxRelayCanceled`, `localUptimeSeconds`, `numOnlineNodes`, `numTotalNodes`, `heapTotalBytes`, `heapFreeBytes`, `noiseFloor`;
- Gesundheitsmesswerte: `healthHeartBpm`, `healthSpO2`, `healthTemperature`;
- Rechnermesswerte: `hostUptimeSeconds`, `hostFreememBytes`, `hostDiskfree1Bytes`, `hostDiskfree2Bytes`, `hostDiskfree3Bytes`, `hostLoad1`, `hostLoad5`, `hostLoad15`, `hostUserString`;
- Paketverarbeitung: `trafficPacketsInspected`, `trafficPositionDedupDrops`, `trafficNodeinfoCacheHits`, `trafficRateLimitDrops`, `trafficUnknownPacketDrops`, `trafficHopExhaustedPackets`, `trafficRouterHopsPreserved`;
- Funksignal und Weiterleitungen: `rxSnr`, `rxRssi`, `hopStart`, `hopLimit`.

<a id="packet-monitor-methoden"></a>

## Paketprotokoll

### `packetMonitor.page`

Gibt einen Abschnitt des LoRa-Paketprotokolls zurück.

Parameter:

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|--------------|
| `request` | string | nein | `older` für ältere, `newer` für neuere Einträge. Jeder andere Wert oder eine leere Zeichenfolge liefert die neuesten Einträge. |
| `query` | `PacketQuery` | nein | Filter für die Einträge. |
| `cursor` | `PageCursor` | nein | Ausgangsposition für die Suche nach älteren oder neueren Einträgen. |
| `limit` | number | nein | Anzahl der Einträge von 1 bis 10000, Standard `200`. |

`PacketQuery`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL` oder fehlend. |
| `packetType` | string | Nach Pakettyp filtern. |
| `transportMechanism` | string | Nach Übertragungsweg filtern. |
| `searchText` | string | Text, nach dem in den Einträgen gesucht wird. |
| `capturedAtFromMillis` | number | Untere Zeitgrenze für `capturedAt` in Millisekunden. |
| `capturedAtToMillis` | number | Obere Zeitgrenze für `capturedAt` in Millisekunden. |

`PageCursor`:

| Feld | Typ |
|------|-----|
| `capturedAt` | number |
| `id` | number |

Ergebnis:

| Feld | Typ |
|------|-----|
| `entries` | `PacketLogEntry[]` |
| `hasNewer` | boolean |
| `hasOlder` | boolean |
| `totalMatchingCount` | number |
| `totalStoredCount` | number |

### `packetMonitor.types`

Parameter: `{"query": PacketQuery}`.

Ergebnis: `{"items":["TEXT_MESSAGE_APP","POSITION_APP",...]}`.

### `packetMonitor.counts`

Parameter: `{"query": PacketQuery}`.

Ergebnis:

| Feld | Typ |
|------|-----|
| `matching` | number |
| `total` | number |

### `packetMonitor.captureState`

Parameter: `{}`.

Ergebnis: `{"enabled": boolean}`.

### `packetMonitor.start`

Schaltet die Aufzeichnung von Paketen ein.

Parameter: `{}`.

Ergebnis: `{"enabled": true}`.

### `packetMonitor.stop`

Schaltet die Aufzeichnung neuer Pakete aus.

Parameter: `{}`.

Ergebnis: `{"enabled": false}`.

### `packetMonitor.clear`

Leert das Paketprotokoll.

Parameter: `{}`.

Ergebnis: `{"matching":0,"total":0}`.

### `PacketLogEntry`

Ein Eintrag im Paketprotokoll. Gson überträgt die Felder des unveränderlichen Java-Objekts als JSON:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `id` | number | Kennung des Datenbankeintrags. |
| `ownerNodeId` | string |
| `capturedAt` | number | Zeitpunkt der Aufzeichnung in Unix-Millisekunden. |
| `direction` | string | `INCOMING`, `OUTGOING`, `INTERNAL`. |
| `packetType` | string |
| `transportMechanism` | string |
| `fromNode` | string |
| `toNode` | string |
| `payloadText` | string |
| `packetBytes` | Byte-Array | JSON-Zahlenarray in der Darstellung, die Gson für ein Java-Array vom Typ `byte[]` erzeugt. |

## Lua-Methoden

Die Methoden unter `lua.*` verwalten MeshApp-IDE-Skripte auf dem Server. Wenn eine Methode `scriptId` entgegennimmt, ist dieser Parameter erforderlich und muss größer als 0 sein.

### `lua.list`

Parameter: `{}`.

Ergebnis: `{"items": LuaScript[]}`.

### `lua.get`

Parameter: `{"scriptId": number}`.

Ergebnis: `{"script": LuaScript}`.

### `lua.draft`

Erstellt einen Skriptentwurf.

Parameter: `{}`.

Ergebnis: `{"script": LuaScript}`.

### `lua.createDefault`

Erstellt ein Skript mit Standardwerten.

Parameter: `{}`.

Ergebnis: `{"script": LuaScript}`.

### `lua.create`

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `name` | string | ja |
| `code` | string | nein |
| `enabled` | boolean | nein |
| `icon` | string | nein |
| `nodeId` | string | nein |
| `botType` | string | nein, Standard `AIR_BOT` |
| `automationName` | string | nein |
| `description` | string | nein |
| `author` | string | nein |

Ergebnis: `{"script": LuaScript}`.

### `lua.save`

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `scriptId` | number | ja |
| `name` | string | ja |
| `code` | string | nein |
| `enabled` | boolean | nein |

Ergebnis: `{"script": LuaScript}`.

### `lua.saveSettings`

Parameter: `scriptId` + Felder aus `lua.create`, außer `code`.

Ergebnis: `{"script": LuaScript}`.

### `lua.delete`

Stoppt und löscht das Skript.

Parameter: `{"scriptId": number}`.

Ergebnis: `lua.list`.

### `lua.importJson`

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `json` | string | ja |

Ergebnis:

| Feld | Typ |
|------|-----|
| `script` | `LuaScript` |
| `updated` | boolean |

### `lua.importExport`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `exportFile` | object | Das von Gson in JSON umgewandelte Objekt `LuaScriptService.LuaScriptExportFile`. |

Ergebnis: wie `lua.importJson`.

### `lua.export`

Parameter: `{"scriptId": number}`.

Ergebnis: `{"json": string}`.

### `lua.runningState`

Parameter: `{"scriptId": number}`.

Ergebnis:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `running` | boolean |
| `paused` | boolean |

### `lua.run`

Startet das Skript. Bei `EXTENSION` wird das Formular auf dem Client angezeigt; Benutzeraktionen werden an das Skript auf dem Server weitergegeben.

Parameter: `{"scriptId": number}`.

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.automation.run`

Führt einen Befehl eines Automatisierungsbots aus.

Parameter:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `command` | `LuaAutomationCommand` |

`LuaAutomationCommand`:

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `handle` | string |
| `text` | string |
| `arguments` | string |
| `argumentTokens` | string[] |
| `requestId` | string |

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.ui.nodeSelection`

Übergibt dem Skript den vom Benutzer gewählten Knoten. Dies ist die Antwort auf das zuvor gesendete Ereignis `lua.ui.nodePick.request`.

Parameter:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `selected` | boolean |
| `chatType` | string |
| `chatKey` | string |
| `node` | `Node`, optional |

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.form.event`

Übergibt dem Skript eine Benutzeraktion an einem Formularelement der Erweiterung.

Parameter:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `componentId` | string |
| `type` | string |
| `value` | Beliebiger JSON-Wert |
| `text` | string |

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.form.valueResult`

Übermittelt dem Server den Wert eines Formularelements. Der Client ruft diese Methode als Antwort auf `lua.form.command` mit `command="value"` auf.

Parameter:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `value` | Beliebiger JSON-Wert |

Ergebnis: `{}`.

### `lua.debug`

Startet das Skript im Debugmodus.

Parameter:

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `breakpoints` | number[] |

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.stop`

Parameter: `{"scriptId": number}`.

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.debugContinue`

Parameter: `{"scriptId": number}`.

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.debugStep`

Parameter: `{"scriptId": number}`.

Ergebnis: Ausführungszustand wie bei `lua.runningState`.

### `lua.debugSnapshot`

Parameter: `{"scriptId": number}`.

Ergebnis:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `snapshot` | `LuaDebugSnapshot`, optional | Aktueller Zustand beim Debuggen. Das Feld fehlt, wenn keine solchen Daten vorliegen. |

`LuaDebugSnapshot` enthält `scriptId`, `line`, `reason` und `variables`: Skriptkennung, angehaltene Zeile, Grund für den Halt und Variablen. Jeder Eintrag in `variables[]` enthält Gültigkeitsbereich `scope`, Name `name` und Wert `value`.

### `lua.kv.list`

Parameter: `{"scriptId": number}`.

Ergebnis:

```json
{
  "items": {
    "key": "value"
  }
}
```

### `lua.kv.set`

Parameter:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `scriptId` | number | ja |
| `key` | string | ja |
| `value` | string | nein |

Ergebnis: `{}`.

### `lua.kv.delete`

Parameter: `{"scriptId": number, "key": "..."}`.

Ergebnis: `{"deleted": boolean}`.

### `lua.kv.clear`

Parameter: `{"scriptId": number}`.

Ergebnis: `{}`.

### `LuaScript`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
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
| `running` | boolean | Nur enthalten, wenn beim Erstellen der Antwort der Ausführungszustand des Skripts abgefragt wurde. |
| `paused` | boolean | Nur enthalten, wenn beim Erstellen der Antwort der Ausführungszustand des Skripts abgefragt wurde. |

### `LuaFormComponentSpec`

Ein Formularelement der Erweiterung wird durch ein JSON-Objekt mit folgenden Feldern beschrieben:

`id`, `type`, `parentId`, `text`, `prompt`, `value`, `items`, `min`, `max`, `disabled`, `visible`, `style`, `orientation`, `width`, `height`, `minWidth`, `minHeight`, `maxWidth`, `maxHeight`, `readOnly`, `wrap`, `monospace`, `grow`, `rows`, `chartType`, `xLabel`, `yLabel`, `xType`, `legend`, `symbols`, `series`.

`series[]` beschreibt die Datenreihen eines Diagramms mit Name `name`, Farbe `color` und Punkten `points`. Jeder Punkt in `points[]` enthält die Koordinaten `x` und `y`.

<a id="settings-methoden"></a>

## Einstellungen des angeschlossenen Geräts

Die Methoden unter `settings.*` arbeiten mit dem Funkgerät der ausgewählten Serververbindung. Vor vielen Vorgängen versucht MeshApp, einen vorübergehenden Verwaltungsschlüssel (`session passkey`) vom Gerät zu erhalten. Die meisten Methoden geben `AdminSnapshot` zurück, ein Objekt mit den aktuellen Geräteeinstellungen.

### `settings.snapshot`

Parameter: `{}`.

Ergebnis: `AdminSnapshot` für das am Server angeschlossene Funkgerät.

### `settings.saveOwner`

Parameter:

| Feld | Typ |
|------|-----|
| `longName` | string |
| `shortName` | string |
| `isLicensed` | boolean |

Ergebnis: `AdminSnapshot`.

### `settings.saveConfigChanges`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `configs` | string[] | Binäre Nachricht `ConfigProtos.Config`, als Base64 codiert. |
| `moduleConfigs` | string[] | Binäre Nachricht `ModuleConfigProtos.ModuleConfig`, als Base64 codiert. |
| `channels` | string[] | Binäre Nachricht `ChannelProtos.Channel`, als Base64 codiert. |

Ergebnis: `AdminSnapshot`.

### `settings.setFixedPosition`

Parameter:

| Feld | Typ |
|------|-----|
| `latDegrees` | number |
| `lonDegrees` | number |
| `altMeters` | number |

Ergebnis: `AdminSnapshot`.

### `settings.removeFixedPosition`

Parameter: `{}`.

Ergebnis: `AdminSnapshot`.

### `settings.setRingtone`

Parameter: `{"ringtone": string}`.

Ergebnis: `AdminSnapshot`.

### `settings.command`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `command` | string | `reboot`, `shutdown`, `syncTime`, `enterDfuMode`. |
| `delaySeconds` | number | Verzögerung für `reboot` und `shutdown`, Standard `0`. |
| `epochSeconds` | number | Für `syncTime`. |

Ergebnis: `AdminSnapshot`.

<a id="remote-admin-methoden"></a>

## Andere Netzknoten verwalten

Die Methoden unter `admin.*` senden Verwaltungsbefehle für Meshtastic über die ausgewählte Serververbindung an einen anderen Netzknoten.

Allgemeine Anforderungen:

- Die ausgewählte Serververbindung muss einen `ProtocolHandler` besitzen.
- Geben Sie den Zielknoten mit `nodeId` oder einer von null verschiedenen `nodeNum` an.
- Das Ziel muss ein anderes Gerät als das direkt am Server angeschlossene Funkgerät sein.
- Der öffentliche Schlüssel des Zielknotens muss bekannt sein.

In allen diesen Methoden wird das Ziel mit folgenden Parametern angegeben:

| Feld | Typ | Pflicht |
|------|-----|---------|
| `nodeId` | string | bedingt |
| `nodeNum` | number | bedingt |

### `admin.load`

Parameter: `nodeId` oder `nodeNum`, wie oben beschrieben.

Ergebnis: `AdminSnapshot`.

### `admin.requestConfig`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Kennung oder Nummer des Zielknotens. |
| `type` | string | Name eines Werts aus `AdminProtos.AdminMessage.ConfigType`, außer `UNRECOGNIZED`. |

Ergebnis: `AdminSnapshot`.

### `admin.requestModuleConfig`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Kennung oder Nummer des Zielknotens. |
| `type` | string | Name eines Werts aus `AdminProtos.AdminMessage.ModuleConfigType`, außer `UNRECOGNIZED`. |

Ergebnis: `AdminSnapshot`.

### `admin.saveOwner`

Parameter: `nodeId` oder `nodeNum` sowie `longName`, `shortName`, `isLicensed`.

Ergebnis: `{"ok": true}`.

### `admin.saveConfigChanges`

Parameter: `nodeId` oder `nodeNum` sowie `configs`, `moduleConfigs`, `channels` wie bei `settings.saveConfigChanges`.

Ergebnis: `{"ok": true}`.

### `admin.setFixedPosition`

Parameter: `nodeId` oder `nodeNum` sowie `latDegrees`, `lonDegrees`, `altMeters`.

Ergebnis: `{"ok": true}`.

### `admin.removeFixedPosition`

Parameter: `nodeId` oder `nodeNum`.

Ergebnis: `{"ok": true}`.

### `admin.setRingtone`

Parameter: `nodeId` oder `nodeNum` sowie `ringtone`.

Ergebnis: `{"ok": true}`.

### `admin.setCannedMessages`

Parameter: `nodeId` oder `nodeNum` sowie `messages`.

Ergebnis: `{"ok": true}`.

### `admin.command`

Parameter:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `nodeId`/`nodeNum` | string/number | Kennung oder Nummer des Zielknotens. |
| `command` | string | Siehe Liste unten. |
| `delaySeconds` | number | Für `reboot`/`shutdown`. |
| `epochSeconds` | number | Für `syncTime`. |
| `location` | string | Speicherort für Befehle zur Datensicherung. Ein Wert aus `AdminProtos.AdminMessage.BackupLocation`, außer `UNRECOGNIZED`. |
| `preserveFavorites` | boolean | Für `resetNodeDb`. |

Verfügbare Befehle:

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

Ergebnis: `{"ok": true}`.

### `admin.refreshConnectionStatus`

Parameter: `nodeId` oder `nodeNum`.

Ergebnis: `AdminSnapshot` plus Feld:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `adminMessage` | string | Binäre Nachricht `AdminProtos.AdminMessage`, als Base64 codiert. |

### `AdminSnapshot`

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `targetNodeNum` | number |
| `node` | `Node` |
| `owner` | string | Binäre Nachricht `MeshProtos.User`, als Base64 codiert. |
| `deviceMetadata` | string | Binäre Nachricht `MeshProtos.DeviceMetadata`, als Base64 codiert. |
| `configs` | string[] | Binäre Nachricht `ConfigProtos.Config`, als Base64 codiert. |
| `moduleConfigs` | string[] | Binäre Nachricht `ModuleConfigProtos.ModuleConfig`, als Base64 codiert. |
| `channels` | string[] | Binäre Nachricht `ChannelProtos.Channel`, als Base64 codiert. |
| `channelCatalogReady` | boolean |
| `ringtone` | string |
| `ringtoneLoaded` | boolean |
| `cannedMessages` | string |
| `cannedMessagesLoaded` | boolean |
| `uiConfig` | string | Binäre Nachricht `DeviceUIProtos.DeviceUIConfig`, als Base64 codiert. |
| `connectionStatus` | string | Binäre Nachricht `ConnStatusProtos.DeviceConnectionStatus`, als Base64 codiert. |
| `queryStatuses` | `QueryStatus[]` |

`QueryStatus`:

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `key` | string |
| `state` | string | `SENT`, `RECEIVED`, `FAILED`. |
| `detail` | string |

<a id="host-events"></a>

## Ereignisse des Servers

### `message.incoming`

Der Server sendet dieses Ereignis beim Empfang einer Nachricht. Der Client zeigt damit eine Systembenachrichtigung an und markiert die Nachricht als ungelesen.

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `message` | `Message` |
| `title` | string |
| `body` | string |

### `message.changed`

Der Server sendet dieses Ereignis, wenn sich Chatdaten ohne Benachrichtigung über eine neue Nachricht ändern, etwa bei einer Reaktion oder geänderten Nachrichtenangaben.

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `targetPacketId` | number |
| `message` | `Message`, optional |

### `message.status`

Der Server sendet dieses Ereignis, wenn sich der Zustellstatus einer ausgehenden Nachricht ändert.

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `chatType` | string |
| `chatKey` | string |
| `packetId` | number |
| `status` | string/null |
| `errorReason` | string/null |
| `message` | `Message` |

### `node.traceroute.result`

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `requestId` | string |
| `status` | string, aktuell `ok` |
| `targetNodeNum` | number |
| `targetNodeId` | string |
| `targetName` | string |
| `responseFromNodeNum` | number |
| `responseFromNodeId` | string |
| `routeData` | Zeichenfolge mit der binären Nachricht `MeshProtos.RouteDiscovery` in Base64 |
| `formattedText` | string |
| `timestamp` | Zahl der Sekunden seit Beginn der Unix-Zeit |
| `nodeNames` | JSON-Objekt: vorzeichenlose Knotennummer als Text → angezeigter Name |

### `packet.monitor.logged`

Ereignisdaten (`payload`): `{"entry": PacketLogEntry}`.

### `packet.monitor.capture`

Ereignisdaten (`payload`): `{"enabled": boolean}`.

### `packet.monitor.cleared`

Ereignisdaten (`payload`): `{}`.

### `lua.runtime.event`

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `type` | string: `INFO`, `OUTPUT`, `WARNING`, `ERROR`, `STARTED`, `STOPPED`, `UI_BOT_NOTICE`, `DEBUG_PAUSED`, `DEBUG_RESUMED` |
| `scriptId` | number |
| `message` | string |
| `errorMessage` | string, optional |
| `payload` | object, optional; für `UI_BOT_NOTICE` ist es `LuaUiBotNotice` |

`LuaUiBotNotice`: `scriptId`, `source`, `name`, `chatType`, `chatKey`, `text`.

### `lua.ui.nodePick.request`

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `requestId` | string |
| `source` | string |
| `name` | string |
| `prompt` | string |
| `query` | string |
| `chatType` | string |
| `chatKey` | string |

Der Client muss die Auswahl mit `lua.ui.nodeSelection` zurückmelden.

### `lua.form.command`

Ereignisdaten (`payload`):

| Feld | Typ |
|------|-----|
| `scriptId` | number |
| `command` | string: `show`, `title`, `clear`, `add`, `update`, `remove`, `value` |
| `requestId` | string |
| `componentId` | string |
| `title` | string |
| `script` | `LuaScript` |
| `spec` | `LuaFormComponentSpec`, optional |

Bei `command="value"` muss der Client den Feldwert mit `lua.form.valueResult` und derselben `requestId` zurückgeben. Die derzeitige Implementierung prüft die Verfügbarkeit entfernter Formulare anhand des Zustands des direkten RPC-Servers. Ob Erweiterungsformulare nutzbar sind, hängt daher von der aktiven Serververbindung ab.

## Beispiele

<a id="requestresponse"></a>

### Anfrage und Antwort

Inhalt der Anfrage vor der Verschlüsselung:

```json
{
  "type": "rpc_request",
  "requestId": "83c2cb3a-132f-4555-bfa5-0f5b8a94944d",
  "method": "system.ping",
  "params": {}
}
```

Antwort:

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

<a id="unknown-method"></a>

### Unbekannte Methode aufrufen

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

<a id="chat-send"></a>

### Chatnachricht senden

Parameter der Anfrage:

```json
{
  "chatType": "channel",
  "chatKey": "0",
  "text": "hello",
  "replyId": 0,
  "clientRequestId": "client-generated-id-1"
}
```

Aufbau des Ergebnisses:

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

<a id="direct-encrypted-line"></a>

### Verschlüsselte TCP-Zeile

Nach der Bestätigung mit `auth_ok` wird eine verschlüsselte Zeile übertragen, nicht mehr das ursprüngliche JSON:

```text
enc1_xs3B9XjglG8FjQg4N0rQLj2r8eOJlYkq7H...
```

<a id="kompatibilitaet"></a>

## Kompatibilität

- Neue Methoden stehen erst nach ausdrücklicher Registrierung in `RpcMethodRegistry` zur Verfügung.
- `METHOD_NOT_FOUND` bedeutet, dass der Server die angefragte Methode nicht unterstützt. Der Client muss diesen Fall behandeln können.
- Clients müssen unbekannte Felder im Ergebnis einer Anfrage und in den Daten eines Ereignisses ignorieren.
- Bei Aufzählungen müssen unbekannte Werte berücksichtigt werden: Eine neue Serverversion kann Werte senden, die ein älterer Client noch nicht kennt.
- Protobuf-Nachrichten werden als Binärdaten in Base64 übertragen, nicht als JSON-Darstellung von Protobuf.
- Ein RPC-Profil muss `ProtocolType.REMOTE_RPC` verwenden. `ConnectionManager` lehnt andere Protokolle für `ConnectionType.REMOTE_RPC` ab.
