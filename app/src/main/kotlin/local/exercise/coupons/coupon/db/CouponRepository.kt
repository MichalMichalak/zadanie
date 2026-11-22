package local.exercise.coupons.coupon.db

import local.exercise.coupons.coupon.Coupon
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Coupon ids keep the merchant's spelling; case-insensitivity is the database's job, not this
 * class's. Every comparison below relies on the `utf8mb4_0900_ai_ci` collation of `coupons.id`
 * (set in /dev-env/schema.sql and asserted by the case-insensitive tests), so `WHERE id = :id`
 * matches any casing while the row still returns the id as it was written.
 */
@Repository
class CouponRepository(private val jdbc: JdbcClient) {

    fun insert(coupon: Coupon) {
        jdbc.sql(
            """
            INSERT INTO coupons (id, merchant_id, created_at, max_usage, current_usage, country)
            VALUES (:id, :merchantId, :createdAt, :maxUsage, :currentUsage, :country)
            """.trimIndent(),
        )
            .param("id", coupon.id)
            .param("merchantId", coupon.merchantId)
            .param("createdAt", Timestamp.from(coupon.createdAt))
            .param("maxUsage", coupon.maxUsage)
            .param("currentUsage", coupon.currentUsage)
            .param("country", coupon.country)
            .update()
    }

    fun find(merchantId: Long, id: String): Coupon? =
        jdbc.sql(
            """
            SELECT id, merchant_id, created_at, max_usage, current_usage, country
            FROM coupons
            WHERE merchant_id = :merchantId AND id = :id
            """.trimIndent(),
        )
            .param("merchantId", merchantId)
            .param("id", id)
            .query { rs, _ ->
                Coupon(
                    id = rs.getString("id"),
                    merchantId = rs.getLong("merchant_id"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    maxUsage = rs.getInt("max_usage"),
                    currentUsage = rs.getInt("current_usage"),
                    country = rs.getString("country"),
                )
            }
            .optional()
            .orElse(null)

    /**
     * Increments `current_usage` only while the usage limit is not reached.
     *
     * The condition makes the check-and-increment a single atomic statement, so concurrent
     * redemptions can never push `current_usage` past `max_usage`.
     *
     * @return true if the row was incremented, false if the coupon is already depleted.
     */
    fun tryIncrementUsage(merchantId: Long, id: String): Boolean =
        jdbc.sql(
            """
            UPDATE coupons
            SET current_usage = current_usage + 1
            WHERE merchant_id = :merchantId AND id = :id AND current_usage < max_usage
            """.trimIndent(),
        )
            .param("merchantId", merchantId)
            .param("id", id)
            .update() == 1
}
