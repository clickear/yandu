# YANDU Translation Spring Boot Starter

Standalone consumer integration module for Spring Boot 3.5 / Java 21 applications. It polls the YANDU public read-only published-translation API; consumer authentication is not required in this release.

## Build and install locally

```sh
mvn install
```

The backend application is in `backend/` and the starter is an independent Maven artifact. The starter is not included in the backend Maven build.

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
    project-id: 1001
    locale: fr-FR
    format: json-flat-v1 # or java-properties-v1
    refresh-interval-ms: 60000
```

Inject `TranslationCatalog` into application services:

```java
String welcome = translations.require("welcome");
```

`find(key)` returns `Optional<String>`, `require(key)` throws `MissingTranslationException` for absent keys, `snapshot()` returns the current immutable map, and `version()` returns the active content SHA-256 (null before the first successful sync).

The starter downloads only after metadata changes, verifies the downloaded UTF-8 content against SHA-256, parses the configured format, and atomically replaces the catalog. If a request, checksum or parse fails, it keeps the last verified snapshot and retries on the next interval. The poller logs only the exception type; it does not log translation content or HTTP response bodies.
