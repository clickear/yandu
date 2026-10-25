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
# Add the deployed React origin to this comma-separated allowlist.
export YANDU_CORS_ALLOWED_ORIGINS='http://localhost:5173'
mvn spring-boot:run
```

Flyway applies versioned schema migrations from `src/main/resources/db/migration`. Do not point the service at the product database until the identity, authorization, backup and migration processes are configured.

Unauthenticated health probes are available at `/actuator/health`, `/livez` and `/readyz`; readiness includes MySQL connectivity, while liveness only reflects the application process state. Health details and component names are hidden from unauthenticated callers.

The API accepts OIDC JWT bearer access tokens. The validated `(issuer, subject)` pair is mapped to an internal numeric user ID. A project owner can add users who have signed in before and grant `ADMIN`, `TRANSLATOR`, `REVIEWER` or `VIEWER`; OWNER cannot be assigned through the member API. Tenant mode and Outbox are not implemented.

Implemented routes are under `/api/v1`: project/member/language and catalog operations, including paged glossary management (`GET/POST /projects/{projectId}/glossary`, archive with `DELETE`); translation workspace search (`GET /projects/{projectId}/translations?namespaceId=…&locale=…&page=1&pageSize=50`), individual translation read/save/submit/review, quality inspection and glossary/exact-source approved translation-memory suggestions; JSON import preview/apply/status/retry; release creation, history, metadata, immutable JSON or Java Properties content download and rollback-as-new-version; project audit history. Workspace search returns source and translation state in one paged query, with IDs serialized as decimal strings. Import requires an `Idempotency-Key` header, stores a version-bound preview, and applies only when the caller confirms its SHA-256 hash. Release format can be selected as `json-flat-v1` or `java-properties-v1` (omitted format defaults to JSON); Properties output is deterministic ASCII with `\\uXXXX` escapes for Java `Properties.load(InputStream)`. Release creation requires every active entry to have an approved translation based on the current source revision and pass the supported `{name}` placeholder check. Audit rows are written in the business transaction and omit full source/translation text.

The React client can use the local browser prototype or the API mode. To enable API mode, copy the repository `.env.example` values into a local `.env` file, set the API URL and public OIDC SPA client settings, register the callback URI with the identity provider, and allow the frontend origin in `YANDU_CORS_ALLOWED_ORIGINS`. API mode has no seeded sample data and requires an actual OIDC sign-in. The identity provider must issue an access token whose issuer and audience match the backend settings.

## Tests

Run `mvn test` with a Java 21 JDK. Domain and application tests do not require a database. MySQL adapter integration tests must run against MySQL 8.0; MySQL 5.7 or H2 are not accepted as substitutes for that compatibility gate.
