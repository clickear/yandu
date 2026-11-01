# YANDU Netty sync module

Optional Netty transport for the YANDU backend and `yandu-translation-spring-boot-starter` consumers. Add this artifact only when Netty is selected; it is the only sync module that brings Netty libraries. The backend activates it with the `sync-netty` Maven profile.

```yaml
yandu:
  translation-sync:
    enabled: true
    mode: netty
    api-base-url: https://yandu.example.com
    netty-host: yandu.example.com # optional; defaults to api-base-url host
    netty-port: 9091
    project-id: 1001
    locales:
      - fr-FR
      - en-US
    format: json-flat-v1
```

On the YANDU backend set `YANDU_NETTY_SYNC_ENABLED=true` and expose `YANDU_NETTY_SYNC_PORT` (default `9091`). The TCP protocol uses newline-delimited UTF-8 JSON; the client opens one subscription per configured locale with project, locale, format and last checksum. The server sends version notices and heartbeats. The client then retrieves and verifies the immutable content over the HTTP API. Reconnects resubscribe with the last active checksum.
