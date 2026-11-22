package local.exercise.coupons.coupon.web

import local.exercise.coupons.coupon.CouponService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val FORWARDED_FOR = "X-Forwarded-For"

@RestController
@RequestMapping("/api/coupons")
class CouponController(private val couponService: CouponService) {

    /**
     * Creates a coupon for the merchant from the `m_id` header.
     *
     * Returns `201` when the coupon was created and `204` when it already existed (a retry of the
     * same request), so creating a coupon is idempotent per `(merchant_id, coupon_id)`. Neither
     * answer carries a body: every field of the coupon came from the merchant, so there is nothing
     * to hand back. `204` rather than an empty `200` says that on purpose.
     */
    @PostMapping
    fun create(
        @RequestHeader("m_id") merchantId: Long,
        @Valid @RequestBody request: CreateCouponRequest,
    ): ResponseEntity<Void> {
        val created = couponService.create(
            merchantId = merchantId,
            couponId = request.couponId,
            maxUsage = request.maxUsage,
            country = request.country,
        )
        val status = if (created) HttpStatus.CREATED else HttpStatus.NO_CONTENT
        return ResponseEntity.status(status).build()
    }

    /**
     * Uses the coupon as the customer from the `c_id` header.
     *
     * Answers `204`: the caller is the customer, and the coupon's country, quota and usage counters
     * are the merchant's data. That the redemption was accepted is the status code.
     */
    @PostMapping("/use")
    fun use(
        @RequestHeader("c_id") customerId: Long,
        @Valid @RequestBody request: UseCouponRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        couponService.use(
            customerId = customerId,
            merchantId = request.merchantId,
            couponId = request.couponId,
            ipAddress = clientIp(httpRequest),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * The caller's address: the first entry of `X-Forwarded-For` when a proxy set one, otherwise the
     * address of the connection itself.
     *
     * The header holds a chain, `client, proxy1, proxy2`, so its first entry is the original caller.
     * It is also client-controlled: it means something only if a proxy in front of this service
     * overwrites it, because otherwise a caller can claim any address — and here the address decides
     * the country.
     */
    private fun clientIp(request: HttpServletRequest): String {
        val firstForwarded = request.getHeader(FORWARDED_FOR)?.substringBefore(',')?.trim()
        return if (firstForwarded.isNullOrEmpty()) request.remoteAddr else firstForwarded
    }
}
