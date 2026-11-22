# Local development environment

```bash
cd dev-env
cp .env.example .env      # first time only
docker compose up -d      # MySQL 8.4 + Redis 7.4
./init-db.sh              # create database, tables and DML-only grants
```

Then run the service from the repository root:

```bash
./gradlew :app:bootRun
```

## Tests

`./gradlew :app:test` needs no infrastructure: the tests start their own MySQL 8.4 and Redis 7.4
containers with Testcontainers, from the same images `docker-compose.yml` uses, and the MySQL
container applies `schema.sql` (Gradle copies it onto the test classpath). Docker is the only
prerequisite, and the compose stack above does not have to be running.

If the Docker daemon is not on the default socket, point Testcontainers at it. With Colima:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The second variable is needed because Ryuk, the container reaper, bind-mounts the socket named by
`DOCKER_HOST`, and Colima's socket lives on the host filesystem where the VM cannot mount it.

## Schema ownership

The application never issues DDL. `schema.sql` is the single definition of the table layout
and is applied only by `init-db.sh`, which runs as `root`. Afterwards the application
credentials (`MYSQL_USER`/`MYSQL_PASSWORD` in `.env`) are stripped of every privilege except
`SELECT, INSERT, UPDATE, DELETE` on that database, so a startup against a missing table fails
loudly instead of silently reshaping the database.

In production the same split applies: DDL is a separate deployment step performed by
privileged tooling, while the running service only has DML rights.

## Scripts

| Command              | Effect                                                        |
| -------------------- | ------------------------------------------------------------- |
| `./init-db.sh`       | Idempotent: creates missing database/tables and re-grants DML |
| `./init-db.sh --reset` | Drops the database first, then recreates it                 |
| `docker compose down -v` | Stops the containers and removes MySQL/Redis data         |
