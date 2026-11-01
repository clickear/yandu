# YANDU WebSocket sync adapter

Optional client transport for `yandu-translation-spring-boot-starter`. Add this artifact when a consumer selects WebSocket; it uses the JDK WebSocket client and does not add Netty.

```yaml
yandu:
  translation-sync:
    enabled: true
    mode: websocket
    api-base-url: https://yandu.example.com
    # Optional override. Defaults to wss://yandu.example.com/api/v1/public/translations/ws
    websocket-url: wss://yandu.example.com/api/v1/public/translations/ws
    project-id: 1001
    locale: fr-FR
    format: json-flat-v1
```

The client sends a JSON subscription after the WebSocket handshake. Release notices and heartbeats cause it to check HTTP metadata, download a changed immutable snapshot, verify SHA-256, and atomically replace the local catalog. No consumer identity token is required for these published-only endpoints.
