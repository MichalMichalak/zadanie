package local.exercise.coupons.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * MySQL and Redis for the tests, started by Testcontainers from the same images
 * `dev-env/docker-compose.yml` uses.
 *
 * The schema is applied by the MySQL container itself (`withInitScript`), using the very file
 * `dev-env/init-db.sh` applies — Gradle copies it onto the test classpath, so tests cannot drift
 * from the environment they are meant to represent.
 *
 * `@ServiceConnection` replaces the datasource and Redis settings from `application.yaml` with the
 * container's coordinates, so the tests need no profile, no environment variables and no running
 * infrastructure: Docker is the only requirement.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfiguration {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = MYSQL

    // The connection name is stated because the redis factory matches on the image name, and
    // `redis:7.4-alpine` is not compatible with the bare `redis` name it looks for.
    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = REDIS

    private companion object {
        // Static so that every Spring context in the test run shares one container each.
        val MYSQL: MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("coupons")
            .withUsername("coupons")
            .withPassword("coupons")
            .withInitScript("schema.sql")

        val REDIS: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
    }
}
