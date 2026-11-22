# Coupon service

A small REST service for issuing and redeeming merchant coupons.

- A **merchant** creates a coupon with an id of their choosing (unique per merchant), a usage
  quota and a country it may be used in.
- A **customer** redeems a coupon once, from a country that matches the coupon's country.
- The service is consistent under concurrency: the quota is never exceeded and the same customer
  can never redeem the same coupon twice.

Stack: Kotlin 2.4, Spring Boot 4.1 (Web MVC, JDBC, Data Redis, Actuator), MySQL 8.4, Redis 7.4,
JDK 21, Gradle 9.8 (wrapper). External country lookup: <https://api.country.is>.

> This file is a reference written by an AI agent. `README.md` is reserved for the hand-written
> version.

---

## Contents

1. [Requirements and behaviour](#requirements-and-behaviour)
2. [Project layout](#project-layout)
3. [Prerequisites](#prerequisites)
4. [Run locally (no application container)](#run-locally-no-application-container)
5. [Run in a container](#run-in-a-container)
6. [Dockerfile explained](#dockerfile-explained)
7. [Configuration](#configuration)
8. [HTTP API](#http-api)
9. [Errors](#errors)
10. [Data model and consistency](#data-model-and-consistency)
11. [Country lookup and cache](#country-lookup-and-cache)
12. [Health, metrics and shutdown](#health-metrics-and-shutdown)
13. [Tests](#tests)
14. [Troubleshooting](#troubleshooting)
15. [Known limitations](#known-limitations)

---

## Requirements and behaviour

Functional:

- Two kinds of actors, merchants and customers; both are rows in MySQL and are not created by this
  API (provisioning is out of scope).
- Coupon id: 1–16 characters of `[0-9A-Za-z_]`, chosen by the merchant, unique **per merchant**.
  Matching is case-insensitive; the stored spelling is preserved.
- Every coupon has `max_usage` (≥ 1) and a two-letter lowercase `country`.
- Redemption requires the customer's country (derived from the request IP) to equal the coupon's
  country, a remaining quota, and no previous redemption of that coupon by that customer.
- `POST /api/coupons` is idempotent per `(merchant_id, coupon_id)`: a retry answers `204` instead
  of creating a duplicate.

Non-functional:

- No race conditions: check-and-increment happens in one SQL statement, and the ledger primary key
  enforces one redemption per customer per coupon. Counter and ledger are written in one
  transaction.
- Retries and concurrent duplicate requests are safe (idempotent create, `409` for a repeated use).

Out of scope: authentication/authorization, merchant/customer management, coupon listing or
deletion, cleaning old usage rows, IPv6, schema migrations by the service.

## Project layout

```
.
├── app/
│   ├── build.gradle                 Kotlin/Spring build, JDK 21 toolchain
│   └── src/
│       ├── main/kotlin/local/exercise/coupons/
│       │   ├── Application.kt           Spring Boot entry point
│       │   ├── ApplicationConfiguration.kt  Clock, TransactionTemplate
│       │   ├── MetricsConfiguration.kt  meter filters (cardinality guard)
│       │   ├── coupon/                  domain service, model, exceptions
│       │   │   ├── db/                  JdbcClient repositories
│       │   │   └── web/                 controller and request DTOs
│       │   ├── providers/               country API client + Redis cache
│       │   └── web/                     error mapping (ApiExceptionHandler)
│       ├── main/resources/application.yaml
│       └── test/kotlin/...              API, concurrency, cache, metrics tests
├── dev-env/                         local MySQL + Redis (docker compose) and schema
│   ├── docker-compose.yml
│   ├── init-db.sh                   privileged schema/grants step
│   └── schema.sql                   the single DDL definition
├── docker/
│   ├── Dockerfile                   runtime image (jar built outside)
│   └── build-image.sh               stages Dockerfile + jar in a temp context
└── gradle/libs.versions.toml        dependency versions
```

The application itself never issues DDL. `dev-env/schema.sql` is the only table definition; it is
applied by `dev-env/init-db.sh` (dev) or by the MySQL test container. The runtime DB user only has
`SELECT, INSERT, UPDATE, DELETE`.

## Prerequisites

| What | Version |
| --- | --- |
| JDK | 21 (Gradle uses the toolchain, but a 21 JDK must be available or downloadable) |
| Docker | for the compose stack and/or the image build |
| MySQL | 8.4, if you do not use `dev-env/docker-compose.yml` |
| Redis | 7.x, if you do not use `dev-env/docker-compose.yml` |
| curl | for the examples |

Everything else (Gradle, Kotlin, Spring) is downloaded by the wrapper. Outbound HTTPS to
`api.country.is` is needed when a coupon is redeemed with an IP that is not cached.

## Run locally (no application container)

Two variants. The first is the intended dev workflow.

### A. Dependencies in Docker, application on the host

```bash
# 1. MySQL 8.4 + Redis 7.4
cd dev-env
cp .env.example .env          # first time only
docker compose up -d
./init-db.sh                  # waits for MySQL, creates DB/tables, limits app grants to DML
cd ..

# 2. Application, with defaults that already point at localhost:3306 / localhost:6379
./gradlew :app:bootRun
```

The API listens on <http://localhost:8080>, the management connector on
<http://localhost:8081>.

Seed the actors the API assumes exist (there is no endpoint for them):

```bash
docker compose -f dev-env/docker-compose.yml exec -T mysql \
  mysql -ucoupons -pcoupons coupons \
  -e "INSERT IGNORE INTO merchants (id) VALUES (1); INSERT IGNORE INTO customers (id) VALUES (10);"
```

Create and redeem a coupon (`8.8.8.8` resolves to the US):

```bash
curl -i -X POST http://localhost:8080/api/coupons \
  -H 'Content-Type: application/json' -H 'm_id: 1' \
  -d '{"coupon_id":"Summer26","max_usage":2,"country":"us"}'
# HTTP/1.1 201

curl -i -X POST http://localhost:8080/api/coupons/use \
  -H 'Content-Type: application/json' -H 'c_id: 10' -H 'X-Forwarded-For: 8.8.8.8' \
  -d '{"merchant_id":1,"coupon_id":"summer26"}'
# HTTP/1.1 204

# Same customer again -> 409 COUPON_ALREADY_USED
```

### B. Fully local dependencies

Install MySQL 8.4 and Redis 7 locally, then:

```bash
mysql -uroot -p <<'SQL'
CREATE DATABASE IF NOT EXISTS coupons
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'coupons'@'%' IDENTIFIED BY 'coupons';
GRANT SELECT, INSERT, UPDATE, DELETE ON coupons.* TO 'coupons'@'%';
SQL
mysql -uroot -p coupons < dev-env/schema.sql

redis-server --requirepass redis   # or start your service

./gradlew :app:bootRun
```

If your credentials differ, override the environment (see [Configuration](#configuration)):

```bash
DB_PASSWORD=secret REDIS_PASSWORD=secret ./gradlew :app:bootRun
```

### Packaging and running the jar directly

```bash
./gradlew :app:bootJar
java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar
```

`app-0.0.1-SNAPSHOT-plain.jar` is the unbootable variant; use the one without `-plain`.

## Run in a container

### 1. Build the image

The Dockerfile does not compile anything: it runs a JRE and one boot jar. Use the build script,
which builds the jar with Gradle and then the image:

```bash
./docker/build-image.sh
```

JDK 21 is needed where the script runs, because the jar is built there. Useful options:

```bash
./docker/build-image.sh --skip-build      # package the existing jar
./docker/build-image.sh --tag app:1.2.3   # custom tag (default: coupon-service)
JAR=path/to/app.jar ./docker/build-image.sh --skip-build   # explicit jar
```

The script stages the Dockerfile and the jar in a temporary directory and uses that as the Docker
build context, then deletes it. Sources, tests, `dev-env/.env` and IDE files are never sent to the
docker daemon, and that is why the repository does not need a `.dockerignore`.

### 2. Prepare MySQL/Redis (same as local development)

```bash
cd dev-env && cp .env.example .env && docker compose up -d && ./init-db.sh && cd ..
```

The schema must exist before the container starts: the image does not create or migrate it, and
the runtime DB user has no DDL rights.

### 3. Run against the compose stack

The compose project is named `coupon-service`, so its services are reachable as `mysql` and
`redis` from a container attached to `coupon-service_default`:

```bash
docker run --rm --name coupon-service \
  -p 8080:8080 \
  --network coupon-service_default \
  -e DB_HOST=mysql -e REDIS_HOST=redis \
  coupon-service
```

Check it:

```bash
# same requests as in the local example, against http://localhost:8080
docker inspect --format '{{.State.Health.Status}}' coupon-service   # healthy
```

The image's `HEALTHCHECK` probes `http://127.0.0.1:8081/actuator/health/liveness` from inside
the container. Port 8081 is deliberately not published; if you want to scrape metrics or health
from the host, publish it on loopback only:

```bash
docker run --rm -p 8080:8080 -p 127.0.0.1:8081:8081 \
  --network coupon-service_default -e DB_HOST=mysql -e REDIS_HOST=redis coupon-service
```

### 4. Run against dependencies on the host

Docker Desktop (macOS/Windows): `host.docker.internal` reaches the host.

```bash
docker run --rm -p 8080:8080 \
  -e DB_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal \
  coupon-service
```

Linux: either use `--network host` (and keep `DB_HOST=localhost`) or add the host gateway:

```bash
docker run --rm -p 8080:8080 \
  --add-host=host.docker.internal:host-gateway \
  -e DB_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal \
  coupon-service
```

Note that MySQL must accept connections from the container's address, and the `coupons` user must
be granted from `%` (the dev `init-db.sh` grants exactly that).

### 5. Stop

```bash
docker stop coupon-service      # SIGTERM: in-flight requests finish, up to 20s
docker compose -f dev-env/docker-compose.yml down      # keep data
docker compose -f dev-env/docker-compose.yml down -v   # delete data
```

## Dockerfile explained

Single stage: `eclipse-temurin:21-jre-alpine` plus one jar.

- **The jar is built outside the image**, by `docker/build-image.sh`, and copied into the build
  context as `application.jar`. The image contains no JDK, Gradle, sources or test files, and
  `docker build` stays fast and needs no dependency downloads. The build itself is already
  reproducible through the Gradle wrapper and the JDK 21 toolchain in `app/build.gradle`, on any
  machine or CI runner that has a JDK; Docker is not needed to produce the artifact.
- **Isolated build context**: the script creates a temporary directory containing only
  `Dockerfile` and `application.jar`, builds from it and removes it. No `.dockerignore` is needed
  because there is nothing in the context to exclude. A CI pipeline that calls `docker build`
  directly must stage those two files itself.
- Consequence: the image is not a from-source build; it packages whichever jar it is given. The
  script rebuilds the jar by default (`--skip-build` opts out), so a stale jar is only possible
  when opting out.
- The image's only artifact is `/app/application.jar`.
- Creates an unprivileged `app` user/group and runs as it. Nothing needs root at runtime.
- `EXPOSE 8080 8081` is documentation only: 8080 is the API, 8081 is the internal management
  connector and should not be published to untrusted networks.
- `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`: 75% of the container
  memory limit for the heap (the rest is metaspace, code cache, stacks, OS); the JVM exits on OOM
  so the orchestrator restarts it instead of leaving a wedged process.
- `HEALTHCHECK`: `wget` against `/actuator/health/liveness` every 10s, 3s timeout, 30s start
  period. Liveness only — DB/Redis outages are reported by `readiness`, which is what a load
  balancer should gate on.
- `ENTRYPOINT` in exec form, so the JVM is PID 1 and receives `SIGTERM` directly; Spring's
  graceful shutdown (`server.shutdown: graceful`, 20s) then drains in-flight requests.

**Build context**: the script builds from a temporary directory with just `Dockerfile` and
`application.jar`; the repository is not the context. There is no `.dockerignore` to keep in sync
with a `COPY` line, and new local file types cannot leak into an image by default. To do the same
by hand:

```bash
tmp=$(mktemp -d)
cp docker/Dockerfile "$tmp/"
cp app/build/libs/*-SNAPSHOT.jar "$tmp/application.jar"
docker build -t coupon-service "$tmp"
rm -rf "$tmp"
```

## Configuration

Configuration lives in `app/src/main/resources/application.yaml`; environment variables are the
intended override mechanism and there are no per-environment profiles. Defaults match
`dev-env/docker-compose.yml` and a local install.

| Variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP API port |
| `MANAGEMENT_PORT` | `8081` | actuator connector; `0` = random |
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `coupons` | database |
| `DB_USER` | `coupons` | DB user (DML only) |
| `DB_PASSWORD` | `coupons` | DB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | `redis` | Redis password (empty = none) |
| `COUNTRY_API_BASE_URL` | `https://api.country.is` | country lookup base URL |
| `COUNTRY_CACHE_TTL` | `1h` | Redis TTL for country answers |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags (image sets this) |

Other Spring Boot knobs work through their usual relaxed-binding names (for example
`LOGGING_LEVEL_LOCAL_EXERCISE_COUPONS=INFO`). `app/src/main/resources/application.yaml` is the
authoritative source.

### How overrides are resolved

Spring Boot merges property sources. For this application the relevant ones, highest precedence
first, are:

1. **Program arguments** — `--server.port=9090`.
2. **`SPRING_APPLICATION_JSON`** — a JSON document with properties.
3. **JVM system properties** — `-Dserver.port=9090` (also passable through `JAVA_TOOL_OPTIONS`).
4. **Environment variables** — `SERVER_PORT=9090`, `SPRING_DATASOURCE_URL=...`.
5. **External config data** — a mounted `application.yaml` pointed at by
   `SPRING_CONFIG_ADDITIONAL_LOCATION`.
6. **Packaged config** — `app/src/main/resources/application.yaml` inside the jar.

So an environment variable beats the packaged YAML, and a CLI argument beats both. The table above
lists the application's own aliases; everything else is reachable by its Spring property name in
relaxed-binding form (dots become underscores, uppercase): `spring.datasource.hikari.maximum-pool-size`
→ `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`.

Note that `DB_*` and `REDIS_*` are placeholders inside the packaged YAML. Setting
`SPRING_DATASOURCE_URL` or `SPRING_DATA_REDIS_HOST` directly also works, and wins over the
placeholder-derived value.

### Overriding a local run

```bash
# environment variables
DB_HOST=127.0.0.1 DB_PASSWORD=secret ./gradlew :app:bootRun

# program arguments: --args belongs to the run task and is passed to the application
./gradlew :app:bootRun --args='--server.port=9090 --country.cache.ttl=5m'

# packaged jar: environment, program arguments, or system properties
DB_HOST=mysql java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar
java -jar app/build/libs/app-0.0.1-SNAPSHOT.jar --server.port=9090
java -Dserver.port=9090 -jar app/build/libs/app-0.0.1-SNAPSHOT.jar
```

### Overriding configuration in the container

Yes. The image is configured entirely from the environment, so every value above can be changed
with `docker run -e`:

```bash
docker run --rm -p 9090:9090 --network coupon-service_default \
  -e SERVER_PORT=9090 -e MANAGEMENT_PORT=9081 \
  -e DB_HOST=mysql -e DB_PASSWORD=secret \
  -e REDIS_HOST=redis -e COUNTRY_CACHE_TTL=10m \
  coupon-service
```

Program arguments are appended to the exec-form entrypoint, so Spring CLI properties work too:

```bash
docker run --rm -p 9090:9090 coupon-service \
  --server.port=9090 --management.server.port=9081 --country.cache.ttl=5m
```

To use a config file instead, mount it read-only (the container user cannot write to `/app`):

```bash
docker run --rm -p 8080:8080 -v "$PWD/config:/config:ro" \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/ \
  coupon-service
```

with overrides in `config/application.yaml`. Environment variables still win over this file because
config data ranks below them. Use `SPRING_CONFIG_LOCATION` instead only when you want to *replace*
the packaged locations entirely.

`JAVA_TOOL_OPTIONS` is set by the image; overriding it replaces the whole value, so keep the flags
you still want:

```bash
docker run --rm -p 8080:8080 \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Xlog:gc" \
  coupon-service
```

For secrets, prefer an env file over `-e` so passwords stay out of shell history and
`docker inspect`:

```bash
docker run --rm --env-file coupon-service.env coupon-service
```

`/actuator/env` is not exposed (only `health,info,prometheus`), so verify overrides with the startup
logs or `docker exec`:

```bash
docker logs coupon-service | head -3                 # "Picked up JAVA_TOOL_OPTIONS: ..."
docker exec coupon-service env | grep -E '^(DB_|REDIS_|SERVER_PORT|MANAGEMENT_PORT)'
```

## HTTP API

Base path `/api/coupons`. JSON uses `snake_case` fields. Authentication is out of scope; the
actor is taken from a header.

### Create a coupon

`POST /api/coupons`

| Header | Required | Meaning |
| --- | --- | --- |
| `m_id` | yes | merchant id (must exist in `merchants`) |
| `Content-Type` | yes | `application/json` |

```json
{ "coupon_id": "Summer26", "max_usage": 2, "country": "us" }
```

| Status | Meaning |
| --- | --- |
| `201 Created` | coupon inserted |
| `204 No Content` | the same merchant already has this coupon id (idempotent retry) |
| `400` | invalid id, `max_usage < 1`, country not two lowercase letters, missing header/body |
| `404` | `MERCHANT_NOT_FOUND` |

No response body on success. Coupon ids are case-insensitive: `Summer26` and `summer26` are the
same coupon, while the stored id keeps the exact spelling from the creating request.

### Use a coupon

`POST /api/coupons/use`

| Header | Required | Meaning |
| --- | --- | --- |
| `c_id` | yes | customer id (must exist in `customers`) |
| `Content-Type` | yes | `application/json` |
| `X-Forwarded-For` | no | first entry is used as the client IP; otherwise the socket address |

```json
{ "merchant_id": 1, "coupon_id": "summer26" }
```

| Status | Meaning |
| --- | --- |
| `204 No Content` | redeemed |
| `403` | `COUNTRY_MISMATCH` — customer's country is not the coupon's |
| `404` | `CUSTOMER_NOT_FOUND`, `COUPON_NOT_FOUND` |
| `409` | `COUPON_DEPLETED` or `COUPON_ALREADY_USED` |
| `503` | `COUNTRY_LOOKUP_FAILED` — the external API or the IP format failed |
| `400` | invalid body/header |

A coupon is identified by `(merchant_id, coupon_id)`, so the same id may exist for two merchants.
Repeating a successful redemption is a `409`, not a silent success — the caller can treat that as
"already consumed".

`X-Forwarded-For` is client-controllable. It is only trustworthy behind a proxy that overwrites it,
because here the address decides the country.

### Examples

```bash
# create
curl -i -X POST http://localhost:8080/api/coupons \
  -H 'Content-Type: application/json' -H 'm_id: 1' \
  -d '{"coupon_id":"WELCOME_1","max_usage":1,"country":"de"}'

# redeem from a German IP (requires outbound HTTPS on the first call)
curl -i -X POST http://localhost:8080/api/coupons/use \
  -H 'Content-Type: application/json' -H 'c_id: 10' -H 'X-Forwarded-For: 85.214.0.1' \
  -d '{"merchant_id":1,"coupon_id":"welcome_1"}'

# wrong country -> 403
curl -i -X POST http://localhost:8080/api/coupons/use \
  -H 'Content-Type: application/json' -H 'c_id: 11' -H 'X-Forwarded-For: 8.8.8.8' \
  -d '{"merchant_id":1,"coupon_id":"welcome_1"}'
```

## Errors

Failures use one body shape:

```json
{ "code": "COUPON_ALREADY_USED", "message": "Customer 10 has already used coupon 'Summer26' of merchant 1" }
```

| `code` | HTTP | When |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | validation, malformed body, missing/invalid header |
| `MERCHANT_NOT_FOUND` | 404 | `m_id` on create does not exist |
| `CUSTOMER_NOT_FOUND` | 404 | `c_id` does not exist |
| `COUPON_NOT_FOUND` | 404 | no `(merchant_id, coupon_id)` |
| `COUPON_ALREADY_USED` | 409 | this customer already redeemed it |
| `COUPON_DEPLETED` | 409 | `current_usage == max_usage` |
| `COUNTRY_MISMATCH` | 403 | customer country ≠ coupon country |
| `COUNTRY_LOOKUP_FAILED` | 503 | IP not IPv4, or upstream API failed/returned no country |

Domain exceptions live in `coupon/CouponExceptions.kt`; HTTP mapping in
`web/ApiExceptionHandler.kt`.

## Data model and consistency

Tables (full DDL in `dev-env/schema.sql`, all InnoDB, `utf8mb4_0900_ai_ci`):

- `merchants(id)` and `customers(id)` — given data, identifiers only.
- `coupons(id, merchant_id, created_at, max_usage, current_usage, country)`,
  PK `(merchant_id, id)`, CHECKs on the counters, FK to merchants.
- `coupon_usage(merchant_id, coupon_id, customer_id, used_at)`,
  PK `(merchant_id, coupon_id, customer_id)`, FKs to coupon and customer.

Why it is safe under concurrency:

- Creation inserts; a duplicate key is checked against the existing row and answered with `204`.
- Redemption:
  1. Customer and coupon are loaded; the country lookup happens **before** the transaction, so an
     external HTTP call never holds a DB connection.
  2. Inside a `TransactionTemplate`:
     - an early `exists` check gives a clean `409` in the common case;
     - `UPDATE coupons SET current_usage = current_usage + 1 WHERE ... AND current_usage < max_usage`
       is a single atomic check-and-increment, so the quota can never be exceeded;
     - `INSERT` into `coupon_usage` is the real "once per customer" guarantee — the primary key
       rejects a second row, even if two requests race past the `exists` check;
     - a duplicate-key on the ledger rolls the transaction back, undoing the counter increment.
- Case-insensitive coupon matching comes from the column collation (`utf8mb4_0900_ai_ci`), not
  from lowercasing in Kotlin, so the merchant's spelling is preserved. Do not switch the column to
  a case-sensitive collation: `CouponRepository` relies on this.
- `coupons.country` is stored lowercase; the provider also returns lowercase (normalized at the
  client boundary).

## Country lookup and cache

- `CountryClient` calls `GET {base}/{(ip)}` on `api.country.is` and lowercases the ISO code.
  Connect timeout 2s, read timeout 5s.
- `CachedCountryProvider` caches in Redis: key `cc_<A.B.C>` (the IPv4 /24 network of the caller),
  value the lowercase country, TTL 1h by default. The assumption is that a /24 is in one country,
  which also bounds the number of distinct keys.
- Only IPv4 is accepted; anything else fails with `COUNTRY_LOOKUP_FAILED`.
- `CountryProvider` is the seam used by tests; `FakeCountryProvider` replaces the real client.

## Health, metrics and shutdown

Management endpoints run on a **separate connector** (`MANAGEMENT_PORT`, default 8081), so the
public application port serves no actuator endpoints:

| Endpoint (port 8081) | Purpose |
| --- | --- |
| `/actuator/health/liveness` | JVM alive; used by the image `HEALTHCHECK` |
| `/actuator/health/readiness` | DB/Redis availability; gate load balancer traffic on this |
| `/actuator/health` | aggregate |
| `/actuator/info` | build info |
| `/actuator/prometheus` | Prometheus text scrape |

Prometheus example:

```yaml
scrape_configs:
  - job_name: coupon-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["coupon-service:8081"]
```

Metrics come from Boot's instrumentation (JVM, HikariCP, Redis, `http.server.requests`), plus
`micrometer-registry-prometheus`. `MetricsConfiguration` rejects meters tagged with domain values
(`coupon_id`, `merchant_id`, `customer_id`, `ip`) and caps distinct `uri` tag values for
`http.server.requests` at 200, so a stray per-request tag cannot explode Prometheus cardinality.
Request metrics use the mapped pattern (`uri="/api/coupons/use"`), not the called path.

Latency histograms are off by design; enable with
`management.metrics.distribution.percentiles-histogram.http.server.requests: true` if p95/p99 are
needed.

Shutdown: `server.shutdown: graceful` with `spring.lifecycle.timeout-per-shutdown-phase: 20s`, so
`SIGTERM`/`docker stop` drains in-flight requests.

## Tests

```bash
./gradlew :app:test
```

- Docker is the only prerequisite; the tests start their own MySQL 8.4 and Redis 7.4 containers
  with Testcontainers (`testing/TestContainersConfiguration.kt`) and do not touch `dev-env`'s
  stack.
- The MySQL container applies `dev-env/schema.sql` (`processTestResources` copies it onto the test
  classpath), so tests cannot drift from the production schema.
- `CouponApiTest` covers validation, idempotent create, case-insensitive ids, `X-Forwarded-For`,
  all error paths and transaction rollback.
- `CouponRedemptionConcurrencyTest` hammers redemption from many threads: quota never exceeded,
  no double redemption.
- `CachedCountryProviderTest` / `CountryClientTest` cover cache keys, /24 normalization, IPv4
  validation and upstream failures.
- `ApplicationTests` checks that management endpoints are on 8081 only and that Prometheus output
  is served. `MetricsConfigurationTest` checks the cardinality guards.

If Docker is not on the default socket (for example Colima):

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The second variable is required because Ryuk bind-mounts the socket named by `DOCKER_HOST`, and
Colima's socket is on the host filesystem, which the VM cannot mount.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Table 'coupons.coupons' doesn't exist` on startup | Schema not applied. Run `dev-env/init-db.sh` (the app has no DDL rights). |
| `MERCHANT_NOT_FOUND` for a fresh setup | `merchants`/`customers` are not seeded; insert ids (see local run). |
| Container cannot reach MySQL | Wrong `DB_HOST` for the network: `mysql` inside `coupon-service_default`, `host.docker.internal` from the host. Also check the MySQL user is granted from `%`. |
| `503 COUNTRY_LOOKUP_FAILED` | No outbound HTTPS, upstream rate limit, or a non-IPv4 address (including an IPv6 socket address when `X-Forwarded-For` is absent). |
| `curl` returns 404 on `/actuator/...` from 8080 | Expected: actuator is on 8081. |
| `docker inspect` health `unhealthy` but API works | 8081 is not reachable inside the container, or startup exceeded the 30s start period; check `docker logs`. |
| Port already in use | Set `SERVER_PORT` / `MANAGEMENT_PORT` (or `-p 127.0.0.1:18080:8080`). |
| Gradle toolchain error | No JDK 21; install it or let the foojay resolver download one. |
| `docker build` fails: `application.jar: not found` | The Dockerfile expects a staged context; use `./docker/build-image.sh`, or stage the jar as shown above. |
| Image runs old code after a change | Use `./docker/build-image.sh` (it rebuilds the jar by default), or rebuild the jar before `--skip-build`. |

Useful commands:

```bash
./gradlew :app:bootRun                 # run locally
./gradlew :app:bootJar                 # build the boot jar
./gradlew :app:test                    # tests (Docker required)
./gradlew :app:dependencies            # dependency report
./docker/build-image.sh                # build jar + image
./docker/build-image.sh --skip-build   # rebuild image from the existing jar
docker compose -f dev-env/docker-compose.yml ps
docker compose -f dev-env/docker-compose.yml logs -f mysql
docker compose -f dev-env/docker-compose.yml exec mysql mysql -uroot -proot coupons
redis-cli -a redis keys 'cc_*'         # cached country answers
```

## Known limitations

- No authentication or authorization: `m_id`/`c_id` headers are trusted, and `X-Forwarded-For`
  is trusted as the client address. Both are placeholders for a real identity/proxy setup.
- Merchant and customer rows are provisioned outside this service; there is no API for them.
- Only IPv4 is supported, and the /24 assumption is deliberate.
- No coupon listing/deletion, no usage history endpoint.
- The app never migrates the schema; schema changes are a separate privileged step.
- The Dockerfile only packages a prebuilt jar; `docker/build-image.sh` wraps it and builds the jar
  by default. A pipeline that invokes `docker build` directly has to stage `Dockerfile` and
  `application.jar` in its build context.
- `coupon_usage` grows forever; a cleanup job for depleted coupons is future work.
