package local.exercise.coupons.coupon

import local.exercise.coupons.testing.FakeCountryConfiguration
import local.exercise.coupons.testing.FakeCountryProvider
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import local.exercise.coupons.testing.TestContainersConfiguration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Hammers the redemption path from many threads to prove that the guarantees hold under
 * concurrency: the coupon is never over-used and a single customer never uses it twice.
 */
@SpringBootTest
@Import(FakeCountryConfiguration::class, TestContainersConfiguration::class)
class CouponRedemptionConcurrencyTest {

    @Autowired
    private lateinit var couponService: CouponService

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var country: FakeCountryProvider

    @BeforeEach
    fun resetDatabase() {
        jdbc.sql("DELETE FROM coupon_usage").update()
        jdbc.sql("DELETE FROM coupons").update()
        jdbc.sql("DELETE FROM customers").update()
        jdbc.sql("DELETE FROM merchants").update()
        jdbc.sql("INSERT INTO merchants (id) VALUES (1)").update()
        (1..60).forEach { jdbc.sql("INSERT INTO customers (id) VALUES (:id)").param("id", it).update() }
        country.country = "us"
    }

    private fun redeemConcurrently(
        customers: List<Long>,
        couponId: String,
    ): List<Result<Coupon>> {
        val pool = Executors.newFixedThreadPool(customers.size)
        val startGate = CountDownLatch(1)
        return try {
            val futures = customers.map { customerId ->
                pool.submit(
                    Callable {
                        startGate.await()
                        runCatching {
                            couponService.use(customerId, merchantId = 1, couponId = couponId, ipAddress = "8.8.8.8")
                        }
                    },
                )
            }
            startGate.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun currentUsage(couponId: String): Int =
        jdbc.sql("SELECT current_usage FROM coupons WHERE merchant_id = 1 AND id = :id")
            .param("id", couponId)
            .query(Int::class.java)
            .single()

    private fun usageRows(couponId: String): Int =
        jdbc.sql("SELECT COUNT(*) FROM coupon_usage WHERE coupon_id = :id")
            .param("id", couponId)
            .query(Int::class.java)
            .single()

    @Test
    fun `usage never exceeds max usage when many customers redeem at once`() {
        couponService.create(merchantId = 1, couponId = "race_usage", maxUsage = 5, country = "us")

        val results = redeemConcurrently((1L..40L).toList(), "race_usage")

        assertEquals(5, results.count { it.isSuccess }, "exactly max_usage redemptions must succeed")
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertEquals(
            emptyList<String>(),
            failures.filterNot { it is CouponDepletedException }.map { it.toString() },
            "the only expected failure is a depleted coupon",
        )
        assertEquals(5, currentUsage("race_usage"))
        assertEquals(5, usageRows("race_usage"))
    }

    @Test
    fun `a customer cannot redeem the same coupon twice concurrently`() {
        couponService.create(merchantId = 1, couponId = "race_customer", maxUsage = 50, country = "us")

        val results = redeemConcurrently(List(20) { 7L }, "race_customer")

        assertEquals(1, results.count { it.isSuccess }, "only one redemption per customer may succeed")
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is CouponAlreadyUsedException })
        assertEquals(1, currentUsage("race_customer"))
        assertEquals(1, usageRows("race_customer"))
    }
}
