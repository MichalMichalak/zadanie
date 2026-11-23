package local.exercise.coupons.coupon.web

import local.exercise.coupons.coupon.db.CouponRepository
import local.exercise.coupons.providers.CountryLookupException
import local.exercise.coupons.providers.CountryProvider
import local.exercise.coupons.testing.FakeCountryConfiguration
import local.exercise.coupons.testing.FakeCountryProvider
import jakarta.servlet.ServletException
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import local.exercise.coupons.testing.TestContainersConfiguration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeCountryConfiguration::class, TestContainersConfiguration::class)
class CouponApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var country: FakeCountryProvider

    @MockitoSpyBean
    private lateinit var coupons: CouponRepository

    @BeforeEach
    fun resetDatabase() {
        jdbc.sql("DELETE FROM coupon_usage").update()
        jdbc.sql("DELETE FROM coupons").update()
        jdbc.sql("DELETE FROM customers").update()
        jdbc.sql("DELETE FROM merchants").update()
        jdbc.sql("INSERT INTO merchants (id) VALUES (1), (2)").update()
        jdbc.sql("INSERT INTO customers (id) VALUES (10), (11), (12)").update()
        country.country = "us"
        country.failure = null
        country.calls.clear()
    }

    private fun createCoupon(
        merchantId: Long = 1,
        couponId: String = "summer26",
        maxUsage: Int = 5,
        country: String = "us",
    ): ResultActions = mockMvc.perform(
        post("/api/coupons")
            .header("m_id", merchantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"coupon_id":"$couponId","max_usage":$maxUsage,"country":"$country"}"""),
    )

    private fun useCoupon(
        customerId: Long,
        merchantId: Long = 1,
        couponId: String = "summer26",
        forwardedFor: String? = null,
    ): ResultActions = mockMvc.perform(
        post("/api/coupons/use")
            .header("c_id", customerId)
            .apply { forwardedFor?.let { header("X-Forwarded-For", it) } }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"merchant_id":$merchantId,"coupon_id":"$couponId"}"""),
    )

    private fun currentUsage(merchantId: Long = 1, couponId: String = "summer26"): Int =
        jdbc.sql("SELECT current_usage FROM coupons WHERE merchant_id = :m AND id = :id")
            .param("m", merchantId)
            .param("id", couponId)
            .query(Int::class.java)
            .single()

    private fun usageRows(): Int =
        jdbc.sql("SELECT COUNT(*) FROM coupon_usage").query(Int::class.java).single()

    /**
     * The invariant the transaction exists for: the counter and the ledger move together. A broken
     * transaction boundary shows up here, whereas a "at most N uses" assertion would not notice.
     */
    private fun assertCounterMatchesLedger(merchantId: Long = 1, couponId: String = "summer26") {
        val counter = currentUsage(merchantId, couponId)
        val ledger = jdbc.sql("SELECT COUNT(*) FROM coupon_usage WHERE merchant_id = :m AND coupon_id = :c")
            .param("m", merchantId)
            .param("c", couponId)
            .query(Int::class.java)
            .single()
        assert(counter == ledger) { "current_usage is $counter but the ledger holds $ledger rows" }
    }

    @Test
    fun `creates a coupon`() {
        createCoupon()
            .andExpect(status().isCreated)
            .andExpect(content().string(""))
    }

    @Test
    fun `repeating the create request is idempotent`() {
        createCoupon().andExpect(status().isCreated)
        createCoupon().andExpect(status().isNoContent)

        val rows = jdbc.sql("SELECT COUNT(*) FROM coupons").query(Int::class.java).single()
        assert(rows == 1) { "expected exactly one coupon, got $rows" }
    }

    @Test
    fun `same coupon id is allowed for another merchant`() {
        createCoupon(merchantId = 1).andExpect(status().isCreated)
        createCoupon(merchantId = 2).andExpect(status().isCreated)

        val rows = jdbc.sql("SELECT COUNT(*) FROM coupons").query(Int::class.java).single()
        assert(rows == 2) { "expected two coupons, got $rows" }
    }

    @Test
    fun `stores the coupon id exactly as given`() {
        createCoupon(couponId = "SuMMer26").andExpect(status().isCreated)

        // Read the raw value: a case-insensitive WHERE would match any spelling.
        val stored = jdbc.sql("SELECT id FROM coupons").query(String::class.java).single()
        assert(stored == "SuMMer26") { "expected the merchant's spelling to be kept, got $stored" }
    }

    /**
     * Guards the collation that makes ids case-insensitive: the primary key must treat the two
     * spellings as one coupon even though the row keeps the first spelling.
     */
    @Test
    fun `coupon id is case insensitive when creating`() {
        createCoupon(couponId = "SUMMER26").andExpect(status().isCreated)
        createCoupon(couponId = "Summer26").andExpect(status().isNoContent)

        val stored = jdbc.sql("SELECT id FROM coupons").query(String::class.java).single()
        assert(stored == "SUMMER26") { "expected the first spelling to win, got $stored" }
    }

    /** The lookup gets the caller's address, so the header parsing is observable through the fake. */
    @Test
    fun `takes the first address of X-Forwarded-For`() {
        createCoupon()

        useCoupon(customerId = 10, forwardedFor = "8.8.8.8, 10.0.0.1, 10.0.0.2")
            .andExpect(status().isNoContent)

        assert(country.calls == listOf("8.8.8.8")) { "expected the client address, got ${country.calls}" }
    }

    @Test
    fun `falls back to the connection address`() {
        createCoupon()

        useCoupon(customerId = 10).andExpect(status().isNoContent)

        // MockMvc calls come from localhost, as there is no proxy in the test.
        assert(country.calls == listOf("127.0.0.1")) { "expected the remote address, got ${country.calls}" }
    }

    @Test
    fun `ignores a blank X-Forwarded-For`() {
        createCoupon()

        useCoupon(customerId = 10, forwardedFor = " , ").andExpect(status().isNoContent)

        assert(country.calls == listOf("127.0.0.1")) { "expected the remote address, got ${country.calls}" }
    }

    @Test
    fun `coupon id is case insensitive when using`() {
        createCoupon(couponId = "summer26")

        useCoupon(customerId = 10, couponId = "SuMMeR26").andExpect(status().isNoContent)

        assert(usageRows() == 1)
        assertCounterMatchesLedger()
    }

    @Test
    fun `rejects an invalid coupon id`() {
        createCoupon(couponId = "Summer-26")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `rejects a non positive max usage`() {
        createCoupon(maxUsage = 0).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unknown country code`() {
        createCoupon(country = "zz")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("country")))
    }

    @Test
    fun `rejects a non ISO country alias`() {
        createCoupon(country = "uk").andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an uppercase country code`() {
        createCoupon(country = "US").andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unknown merchant`() {
        createCoupon(merchantId = 999)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MERCHANT_NOT_FOUND"))
    }

    @Test
    fun `uses a coupon`() {
        createCoupon()

        useCoupon(customerId = 10)
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        assert(currentUsage() == 1)
        assert(usageRows() == 1)
        assertCounterMatchesLedger()
    }

    @Test
    fun `customer cannot use the same coupon twice`() {
        createCoupon()
        useCoupon(customerId = 10).andExpect(status().isNoContent)

        useCoupon(customerId = 10)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_ALREADY_USED"))

        assert(currentUsage() == 1)
        assert(usageRows() == 1)
        assertCounterMatchesLedger()
    }

    @Test
    fun `coupon cannot be used more times than allowed`() {
        createCoupon(maxUsage = 1)
        useCoupon(customerId = 10).andExpect(status().isNoContent)

        useCoupon(customerId = 11)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("COUPON_DEPLETED"))

        assert(currentUsage() == 1)
        assert(usageRows() == 1)
        assertCounterMatchesLedger()
    }

    @Test
    fun `coupon cannot be used from another country`() {
        createCoupon(country = "de")

        useCoupon(customerId = 10)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("COUNTRY_MISMATCH"))

        assert(currentUsage() == 0)
        assertCounterMatchesLedger()
    }

    @Test
    fun `unknown coupon is reported`() {
        useCoupon(customerId = 10, couponId = "nope")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
    }

    @Test
    fun `unknown customer is reported`() {
        createCoupon()
        useCoupon(customerId = 999)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
    }

    @Test
    fun `failed country lookup makes the request fail`() {
        createCoupon()
        country.failure = CountryLookupException("boom")

        useCoupon(customerId = 10)
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("COUNTRY_LOOKUP_FAILED"))

        assertCounterMatchesLedger()
    }

    @Test
    fun `failure after the counter update rolls everything back`() {
        createCoupon(maxUsage = 3)

        // The last read inside the transaction is made to fail, after the counter and the ledger row
        // were already written. Outside a transaction both writes would stick.
        doCallRealMethod().doThrow(IllegalStateException("boom"))
            .`when`(coupons)
            .find(anyLong(), anyString())

        val failure = assertThrows<ServletException> { useCoupon(customerId = 10) }
        assert(failure.cause is IllegalStateException) { "expected the stubbed failure, got ${failure.cause}" }

        assert(currentUsage() == 0) { "the increment must have been rolled back" }
        assert(usageRows() == 0) { "the ledger row must have been rolled back" }
        assertCounterMatchesLedger()
    }
}
