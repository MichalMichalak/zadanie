package local.exercise.coupons.coupon.web

import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import local.exercise.coupons.testing.FakeCountryConfiguration
import local.exercise.coupons.testing.FakeCountryProvider
import local.exercise.coupons.testing.TestContainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Opt-in stress test: opens many concurrent HTTP connections through the real server, not through
 * MockMvc, so Tomcat and the Hikari pool are exercised as well.
 *
 * Tagged `stress` and excluded from the default `test` task, because it is slower and noisier than
 * the deterministic concurrency test in
 * [local.exercise.coupons.coupon.CouponRedemptionConcurrencyTest]. Run it with
 * `./gradlew :app:stressTest` when touching redemption, SQL or pool settings, or in a nightly job.
 * The assertions are still invariants, never timings.
 */
@Tag("stress")
@Timeout(180)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The management connector gets a random port too, so the test never depends on 8081 being free.
    properties = ["management.server.port=0"],
)
@Import(FakeCountryConfiguration::class, TestContainersConfiguration::class)
class CouponApiStressTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var country: FakeCountryProvider

    @Autowired
    private lateinit var dataSource: DataSource

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @BeforeEach
    fun resetDatabase() {
        jdbc.sql("DELETE FROM coupon_usage").update()
        jdbc.sql("DELETE FROM coupons").update()
        jdbc.sql("DELETE FROM customers").update()
        jdbc.sql("DELETE FROM merchants").update()
        jdbc.sql("INSERT INTO merchants (id) VALUES (1)").update()
        (1L..120L).forEach { jdbc.sql("INSERT INTO customers (id) VALUES (:id)").param("id", it).update() }
        country.country = "us"
    }

    @Test
    fun `many concurrent connections never exceed the coupon quota`() {
        createCoupon("stress_quota", maxUsage = 40)

        val responses = redeemConcurrently((1L..100L).toList(), "stress_quota")

        assertEquals(40, responses.count { it.statusCode() == 204 }, "exactly max_usage requests must succeed")
        val rejected = responses.filter { it.statusCode() != 204 }
        assertEquals(60, rejected.size)
        assertTrue(
            rejected.all { it.statusCode() == 409 && it.body().contains("COUPON_DEPLETED") },
            "rejections must be depleted coupons, got: ${rejected.map { "${it.statusCode()} ${it.body()}" }}",
        )
        assertCounterAndLedger("stress_quota", expected = 40)
        assertPoolIsIdle()
    }

    @Test
    fun `many concurrent connections for one customer redeem the coupon once`() {
        createCoupon("stress_customer", maxUsage = 50)

        val responses = redeemConcurrently(List(40) { 7L }, "stress_customer")

        assertEquals(1, responses.count { it.statusCode() == 204 }, "only one redemption per customer may succeed")
        val rejected = responses.filter { it.statusCode() != 204 }
        assertEquals(39, rejected.size)
        assertTrue(
            rejected.all { it.statusCode() == 409 && it.body().contains("COUPON_ALREADY_USED") },
            "rejections must be duplicate redemptions, got: ${rejected.map { "${it.statusCode()} ${it.body()}" }}",
        )
        assertCounterAndLedger("stress_customer", expected = 1)
        assertPoolIsIdle()
    }

    private fun createCoupon(couponId: String, maxUsage: Int) {
        val response = post(
            "/api/coupons",
            mapOf("m_id" to "1"),
            """{"coupon_id":"$couponId","max_usage":$maxUsage,"country":"us"}""",
        )
        assertEquals(201, response.statusCode(), "coupon creation failed: ${response.body()}")
    }

    private fun redeem(customerId: Long, couponId: String): HttpResponse<String> =
        post(
            "/api/coupons/use",
            mapOf("c_id" to customerId.toString(), "X-Forwarded-For" to "8.8.8.8"),
            """{"merchant_id":1,"coupon_id":"$couponId"}""",
        )

    private fun redeemConcurrently(
        customers: List<Long>,
        couponId: String,
    ): List<HttpResponse<String>> {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val startGate = CountDownLatch(1)
            val futures = customers.map { customerId ->
                executor.submit(
                    Callable {
                        startGate.await()
                        redeem(customerId, couponId)
                    },
                )
            }
            startGate.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        }
    }

    private fun post(
        path: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertCounterAndLedger(couponId: String, expected: Int) {
        val counter = jdbc.sql("SELECT current_usage FROM coupons WHERE merchant_id = 1 AND id = :id")
            .param("id", couponId)
            .query(Int::class.java)
            .single()
        val ledger = jdbc.sql("SELECT COUNT(*) FROM coupon_usage WHERE merchant_id = 1 AND coupon_id = :id")
            .param("id", couponId)
            .query(Int::class.java)
            .single()
        assertEquals(expected, counter, "usage counter")
        assertEquals(counter, ledger, "ledger rows must match the counter")
    }

    /** A leaked connection would stay borrowed after every response has been written. */
    private fun assertPoolIsIdle() {
        val pool = dataSource as? HikariDataSource ?: error("expected the datasource to be a HikariDataSource")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pool.hikariPoolMXBean.activeConnections > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(0, pool.hikariPoolMXBean.activeConnections, "connections were not returned to the pool")
    }
}
