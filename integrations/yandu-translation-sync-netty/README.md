# YANDU Netty sync adapter

Optional client transport for `yandu-translation-spring-boot-starter`. Add this artifact only when the consumer selects Netty; it is the only consumer artifact that brings Netty libraries.

```yaml
yandu:
  translation-sync:
    enabled: true
    mode: netty
    api-base-url: https://yandu.example.com
    netty-host: yandu.example.com # optional; defaults to api-base-url host
    netty-port: 9091
    project-id: 1001
    locale: fr-FR
    format: json-flat-v1
```

On the YANDU backend set `YANDU_NETTY_SYNC_ENABLED=true` and expose `YANDU_NETTY_SYNC_PORT` (default `9091`). The TCP protocol uses newline-delimited UTF-8 JSON; the client subscribes with project, locale, format and last checksum. The server sends version notices and heartbeats. The client then retrieves and verifies the immutable content over the HTTP API. Reconnects resubscribe with the last active checksum.
