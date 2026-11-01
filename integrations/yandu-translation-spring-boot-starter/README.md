# YANDU Translation Spring Boot Starter

Base consumer integration module for Spring Boot 3.5 / Java 21 applications. It polls the YANDU public read-only published-translation API; consumer authentication is not required in this release. Real-time transports are separate optional artifacts so consumers do not pull Netty or WebSocket implementation code unless selected.

## Build and install locally

The root Maven project aggregates the backend and optional consumer artifacts. The backend adds its server transport only through the matching Maven profile; consumer applications add the base starter and only their selected transport. Build the base starter and only the selected artifact from the repository root:

```sh
mvn -pl integrations/yandu-translation-spring-boot-starter -am install
```

For a real-time mode, select its artifact; Maven builds the base starter transitively. These commands are alternatives, so a consumer only installs the transport it uses:

```sh
mvn -pl integrations/yandu-translation-sync-netty -am install
mvn -pl integrations/yandu-translation-sync-websocket -am install
```

The Netty adapter brings Netty dependencies. The WebSocket adapter uses the JDK WebSocket client and adds no Netty dependency.

## Add to a consumer application

```xml
<dependency>
    <groupId>com.yandu</groupId>
    <artifactId>yandu-translation-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure the YANDU base URL, project ID, target locale and published format:

```yaml
yandu:
  translation-sync:
    enabled: true
    api-base-url: http://localhost:8080
    mode: polling # polling, netty, or websocket
    project-id: 1001
    locale: fr-FR
    format: json-flat-v1 # or java-properties-v1
    refresh-interval-ms: 60000
    # Netty mode defaults to api-base-url's host and port 9091.
    netty-port: 9091
    # websocket-url is optional; defaults to ws(s)://<api-host>/api/v1/public/translations/ws
```

For `mode: polling`, only the base dependency is needed. For `mode: netty`, also add `com.yandu:yandu-translation-sync-netty`; for `mode: websocket`, add `com.yandu:yandu-translation-sync-websocket`. Inject `TranslationCatalog` into application services:

```java
String welcome = translations.require("welcome");
```

`find(key)` returns `Optional<String>`, `require(key)` throws `MissingTranslationException` for absent keys, `snapshot()` returns the current immutable map, and `version()` returns the active content SHA-256 (null before the first successful sync).

`polling` checks metadata every 60 seconds by default. `netty` opens a TCP connection to the configured Netty sync port and sends one newline-delimited JSON `subscribe` message; `websocket` connects to the public WebSocket URL and sends the same JSON subscription. Both real-time modes reconnect after disconnects and treat release notices and heartbeats as triggers to check the HTTP metadata API.

All modes download only after metadata changes, verify the downloaded UTF-8 content against SHA-256, parse the configured format, and atomically replace the catalog. If a request, checksum or parse fails, they keep the last verified snapshot and retry. Real-time modes require the matching server transport to be enabled/reachable. The starter logs only exception types; it does not log translation content or HTTP response bodies.
