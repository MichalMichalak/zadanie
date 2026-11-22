package local.exercise.coupons.coupon.db

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Merchants are provisioned elsewhere (they are given for this task), so we only check existence. */
@Repository
class MerchantRepository(private val jdbc: JdbcClient) {

    fun exists(merchantId: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM merchants WHERE id = :id)")
            .param("id", merchantId)
            .query(Boolean::class.java)
            .single()
}
