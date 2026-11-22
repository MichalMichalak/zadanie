package local.exercise.coupons.coupon

import local.exercise.coupons.coupon.db.CouponRepository
import local.exercise.coupons.coupon.db.CouponUsageRepository
import local.exercise.coupons.coupon.db.CustomerRepository
import local.exercise.coupons.coupon.db.MerchantRepository
import local.exercise.coupons.providers.CountryProvider
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Coupon use cases. Knows nothing about HTTP: callers pass plain values in and get domain objects
 * or [CouponException]s back.
 */
@Service
class CouponService(
    private val coupons: CouponRepository,
    private val usages: CouponUsageRepository,
    private val merchants: MerchantRepository,
    private val customers: CustomerRepository,
    private val countryProvider: CountryProvider,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Creates the coupon. The coupon id is supplied by the merchant and is unique per merchant,
     * which makes creation idempotent: repeating the request returns the already existing coupon
     * instead of creating a second one.
     *
     * The id is stored exactly as the merchant wrote it, casing included: it is their identifier,
     * not ours. Case-insensitive matching comes from the collation of the `coupons.id` column
     * (`utf8mb4_0900_ai_ci`, see dev-env/schema.sql), so this class never lowercases it. Only the
     * country is normalized here, because that is the provider's vocabulary, not the merchant's.
     *
     * @return true when this call inserted the coupon, false when it was already there.
     */
    fun create(merchantId: Long, couponId: String, maxUsage: Int, country: String): Boolean {
        if (!merchants.exists(merchantId)) {
            throw MerchantNotFoundException(merchantId)
        }

        val coupon = Coupon(
            id = couponId,
            merchantId = merchantId,
            createdAt = clock.instant(),
            maxUsage = maxUsage,
            currentUsage = 0,
            country = country.lowercase(),
        )

        return try {
            coupons.insert(coupon)
            true
        } catch (ex: DuplicateKeyException) {
            // Idempotent retry. Confirm the conflicting row is this merchant's coupon and not some
            // other constraint, so a genuine failure is not reported as "already exists".
            coupons.find(merchantId, couponId) ?: throw ex
            log.debug("Coupon {}/{} already exists, nothing to do", merchantId, couponId)
            false
        }
    }

    /**
     * Uses the coupon. The country lookup happens before the transaction so that the external
     * API call does not hold a database connection.
     *
     * Any casing of the id finds the coupon, because the lookup compares against the collation of
     * `coupons.id` rather than the request string; the row read back carries the stored spelling,
     * and that is what the ledger records.
     */
    fun use(customerId: Long, merchantId: Long, couponId: String, ipAddress: String): Coupon {
        if (!customers.exists(customerId)) {
            throw CustomerNotFoundException(customerId)
        }

        val coupon = coupons.find(merchantId, couponId)
            ?: throw CouponNotFoundException(merchantId, couponId)

        // Both sides are lowercase: the provider normalizes upstream, the column is validated.
        val customerCountry = countryProvider.getByIp(ipAddress)
        if (customerCountry != coupon.country) {
            throw CountryMismatchException(coupon.country, customerCountry)
        }

        return redeemInTransaction(customerId, coupon)
    }

    /**
     * Owns the transaction boundary, so the guarantee cannot be lost by changing the caller:
     * the usage counter and the ledger row are written together or not at all.
     */
    private fun redeemInTransaction(customerId: Long, coupon: Coupon): Coupon =
        transactionTemplate.execute { redeem(customerId, coupon) }

    /** Body of the redemption; expects to be called inside [redeemInTransaction]. */
    private fun redeem(customerId: Long, coupon: Coupon): Coupon {
        val alreadyUsed = usages.exists(coupon.merchantId, coupon.id, customerId)
        if (alreadyUsed) {
            throw CouponAlreadyUsedException(customerId, coupon.merchantId, coupon.id)
        }

        // Check-and-increment in one statement, so concurrent redemptions cannot exceed max_usage.
        if (!coupons.tryIncrementUsage(coupon.merchantId, coupon.id)) {
            throw CouponDepletedException(coupon.merchantId, coupon.id)
        }

        try {
            usages.insert(coupon.merchantId, coupon.id, customerId, clock.instant())
        } catch (_: DuplicateKeyException) {
            // Raced with a concurrent redemption of the same coupon by the same customer.
            // Rethrowing rolls the transaction back, undoing the increment above.
            throw CouponAlreadyUsedException(customerId, coupon.merchantId, coupon.id)
        }

        return coupons.find(coupon.merchantId, coupon.id)
            ?: throw CouponNotFoundException(coupon.merchantId, coupon.id)
    }
}
