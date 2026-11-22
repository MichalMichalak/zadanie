package local.exercise.coupons.coupon

/**
 * Business failure raised by [CouponService].
 *
 * Carries only domain information: mapping a failure to an HTTP status and an error code is the
 * responsibility of the web layer, so the service stays transport agnostic.
 */
sealed class CouponException(message: String) : RuntimeException(message)

class MerchantNotFoundException(merchantId: Long) :
    CouponException("Merchant $merchantId not found")

class CustomerNotFoundException(customerId: Long) :
    CouponException("Customer $customerId not found")

class CouponNotFoundException(merchantId: Long, couponId: String) :
    CouponException("Coupon '$couponId' of merchant $merchantId not found")

class CouponDepletedException(merchantId: Long, couponId: String) :
    CouponException("Coupon '$couponId' of merchant $merchantId is depleted")

class CouponAlreadyUsedException(customerId: Long, merchantId: Long, couponId: String) :
    CouponException("Customer $customerId has already used coupon '$couponId' of merchant $merchantId")

class CountryMismatchException(couponCountry: String, customerCountry: String) :
    CouponException("Coupon is limited to country '$couponCountry', customer's country is '$customerCountry'")
