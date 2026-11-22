package local.exercise.coupons.coupon.web

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive

const val COUPON_ID_PATTERN = "^[0-9A-Za-z_]{1,16}$"
const val COUNTRY_PATTERN = "^[a-z]{2}$"

/** Body of `POST /api/coupons`. Coupon ids are case-insensitive; the stored id keeps this casing. */
data class CreateCouponRequest(
    @field:Pattern(regexp = COUPON_ID_PATTERN, message = "coupon_id must be 1-16 characters of letters, digits or underscore")
    val couponId: String,
    @field:Min(value = 1, message = "max_usage must be at least 1")
    val maxUsage: Int,
    @field:Pattern(regexp = COUNTRY_PATTERN, message = "country must be two lowercase letters")
    val country: String,
)

/** Body of `POST /api/coupons/use`. */
data class UseCouponRequest(
    @field:Positive(message = "merchant_id must be positive")
    val merchantId: Long,
    @field:Pattern(regexp = COUPON_ID_PATTERN, message = "coupon_id must be 1-16 characters of letters, digits or underscore")
    val couponId: String,
)
