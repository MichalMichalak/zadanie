package local.exercise.coupons.coupon.db

import java.sql.Timestamp
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CouponUsageRepository(private val jdbc: JdbcClient) {

    fun exists(merchantId: Long, couponId: String, customerId: Long): Boolean =
        jdbc.sql(
            """
            SELECT COUNT(*)
            FROM coupon_usage
            WHERE merchant_id = :merchantId AND coupon_id = :couponId AND customer_id = :customerId
            """.trimIndent(),
        )
            .param("merchantId", merchantId)
            .param("couponId", couponId)
            .param("customerId", customerId)
            .query(Long::class.java)
            .single() > 0

    /**
     * Records one redemption. The primary key of `coupon_usage` is what actually guarantees
     * "once per customer": a second row for the same customer is rejected by the database.
     *
     * @throws DuplicateKeyException when this customer already used this coupon.
     */
    fun insert(merchantId: Long, couponId: String, customerId: Long, usedAt: Instant) {
        jdbc.sql(
            """
            INSERT INTO coupon_usage (merchant_id, coupon_id, customer_id, used_at)
            VALUES (:merchantId, :couponId, :customerId, :usedAt)
            """.trimIndent(),
        )
            .param("merchantId", merchantId)
            .param("couponId", couponId)
            .param("customerId", customerId)
            .param("usedAt", Timestamp.from(usedAt))
            .update()
    }
}
