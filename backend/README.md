# YANDU Backend

Java 21 / Spring Boot 3.5 / Spring Modulith 1.4 / MyBatis-Plus 3.5 backend module. MySQL 8.0 is the primary database. Workspace, Catalog, Translation, immutable JSON and Java Properties release snapshots, OIDC resource-server security and versioned REST endpoints are in place. An OIDC issuer and API audience must be configured before startup; there is no local password or demo login. IDs are app-generated `BIGINT` Snowflake IDs, serialized as strings at the JSON boundary. The default namespace shares the new project's numeric ID in its separate table.

## Local run

Start the local MySQL 8.0 service from this directory. Docker Compose requires explicit local passwords and stores database data in a named volume; it does not seed application users or demo business data.

```sh
export YANDU_MYSQL_PASSWORD='choose-a-local-password'
export YANDU_MYSQL_ROOT_PASSWORD='choose-a-different-local-password'
docker compose up -d mysql
```

Then configure the application environment. The service has no demo credentials or seeded success state. Tenant mode and Outbox are not part of this implementation phase.

```sh
export YANDU_DB_URL='jdbc:mysql://127.0.0.1:3306/yandu?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
export YANDU_DB_USER='yandu'
export YANDU_DB_PASSWORD="$YANDU_MYSQL_PASSWORD"
export YANDU_OIDC_ISSUER_URI='https://your-idp.example/issuer'
export YANDU_OIDC_AUDIENCE='yandu-api'
# Machine translation provider: libretranslate (default) or openai-compatible.
export YANDU_MT_PROVIDER='openai-compatible'
export YANDU_MT_BASE_URL='https://your-translation-service.example'
# OpenAI-compatible APIs usually use a /v1 base URL and a deployed model name.
export YANDU_MT_MODEL='your-model-name'
# Set the API key through a secret store; leave unset for an unauthenticated self-hosted endpoint.
export YANDU_MT_API_KEY=''
# Add the deployed React origin to this comma-separated allowlist.
export YANDU_CORS_ALLOWED_ORIGINS='http://localhost:5173'
# Run from the repository root so Maven also builds the shared sync contract.
mvn -pl backend -am spring-boot:run
```

Flyway applies versioned schema migrations from `src/main/resources/db/migration`. Do not point the service at the product database until the identity, authorization, backup and migration processes are configured.

Unauthenticated health probes are available at `/actuator/health`, `/livez` and `/readyz`; readiness includes MySQL connectivity, while liveness only reflects the application process state. Health details and component names are hidden from unauthenticated callers.

The API accepts OIDC JWT bearer access tokens. The validated `(issuer, subject)` pair is mapped to an internal numeric user ID. A project owner can add users who have signed in before and grant `ADMIN`, `TRANSLATOR`, `REVIEWER` or `VIEWER`; OWNER cannot be assigned through the member API. Tenant mode and Outbox are not implemented.

Implemented routes are under `/api/v1`: project/member/language and catalog operations, including paged glossary management (`GET/POST /projects/{projectId}/glossary`, archive with `DELETE`), single-entry reads used to obtain the current directory version before a rename/move, and paged entry search; translation workspace search (`GET /projects/{projectId}/translations?namespaceId=…&locale=…&page=1&pageSize=50`), individual translation read/save/submit/review, quality inspection and glossary/exact-source approved translation-memory suggestions; asynchronous machine translation jobs with status, paged item results and retry; JSON import preview/apply/status/retry; release creation, history, metadata, immutable JSON or Java Properties content download and rollback-as-new-version; project audit history. Workspace search returns source and translation state in one paged query, with IDs serialized as decimal strings. Import and machine-translation creation require an `Idempotency-Key`; machine output is saved as a draft only when the source and empty-translation versions still match the queued snapshot. Release format can be selected as `json-flat-v1` or `java-properties-v1` (omitted format defaults to JSON); Properties output is deterministic ASCII with `\\uXXXX` escapes for Java `Properties.load(InputStream)`. Release creation requires every active entry to have a nonblank approved translation based on the current source revision and valid named-placeholder syntax and counts; doubled braces represent literal text. Audit rows omit full source/translation text.

Machine translation supports LibreTranslate-compatible `POST /translate` and OpenAI-compatible `POST /chat/completions` JSON protocols. Select with `YANDU_MT_PROVIDER` (`libretranslate` by default or `openai-compatible`). Configure `YANDU_MT_BASE_URL`; OpenAI-compatible services also require `YANDU_MT_MODEL`. When authentication is needed, `YANDU_MT_API_KEY` is sent only by the backend. Missing required configuration makes job creation and failed-item retry return HTTP 503; retry leaves failed items unchanged until a provider is available. Unsupported language pairs or provider failures appear as failed task items and can be retried after fixing the provider configuration. The worker polls durable MySQL rows, processes up to four entries concurrently, and recovers work left running by a stopped process. A task stores source text snapshots for the worker; these snapshots are not returned in APIs or written to audit logs. Task item results include the entry's namespace ID so the UI can locate that entry in the translation workspace, including when the task spans namespaces.

The React client can use the local browser prototype or the API mode. To enable API mode, copy the repository `.env.example` values into a local `.env` file, set the API URL and public OIDC SPA client settings, register the callback URI with the identity provider, and allow the frontend origin in `YANDU_CORS_ALLOWED_ORIGINS`. API mode has no seeded sample data and requires an actual OIDC sign-in. The identity provider must issue an access token whose issuer and audience match the backend settings.

## Published translation consumers

Consumer reads are public and limited to already published snapshots. The HTTP snapshot API is available at `/api/v1/public/projects/{projectId}/translations/{locale}/latest` and its `/content` child. Realtime transports are optional modules: start with `mvn -pl backend -am -Psync-netty spring-boot:run` to add Netty TCP, or `mvn -pl backend -am -Psync-websocket spring-boot:run` to add WebSocket. Both profiles can be selected together when both server transports are needed. Netty listens on `YANDU_NETTY_SYNC_PORT` (default `9091`) when `YANDU_NETTY_SYNC_ENABLED=true`; WebSocket listens at `/api/v1/public/translations/ws` when `YANDU_SYNC_WEBSOCKET_ENABLED=true`. Each long connection sends a JSON `subscribe` message with `projectId`, `locale`, `format` and optional `afterChecksum`; notifications contain metadata only, and consumers fetch and verify the HTTP snapshot. Heartbeats prompt metadata refresh so consumers recover from reconnects and updates published by another application node. These interfaces do not change OIDC protection on workspace APIs.

## Tests

Run `mvn test` with a Java 21 JDK. Domain and application tests do not require a database. MySQL adapter integration tests must run against MySQL 8.0; MySQL 5.7 or H2 are not accepted as substitutes for that compatibility gate.
