package local.exercise.coupons.coupon

import java.time.Instant

/** A coupon as stored in the `coupons` table. */
data class Coupon(
    val id: String,
    val merchantId: Long,
    val createdAt: Instant,
    val maxUsage: Int,
    val currentUsage: Int,
    val country: String,
)
