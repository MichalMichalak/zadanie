package local.exercise.coupons.coupon.db

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Customers are provisioned elsewhere (they are given for this task), so we only check existence. */
@Repository
class CustomerRepository(private val jdbc: JdbcClient) {

    fun exists(customerId: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM customers WHERE id = :id)")
            .param("id", customerId)
            .query(Boolean::class.java)
            .single()
}
