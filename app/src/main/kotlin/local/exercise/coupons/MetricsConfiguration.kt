package local.exercise.coupons

import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Guards the metrics endpoint against cardinality explosions.
 *
 * Every distinct combination of tag values is its own time series, so a tag carrying a value that
 * grows with the traffic — a coupon id, a customer id, an IP address — turns one metric into
 * millions and takes the monitoring backend down with it. That is a failure you discover in
 * production and cannot fix by rolling back a dashboard, so the registry refuses such meters here.
 *
 * Request metrics are tagged with the mapped pattern (`uri=/api/coupons/use`), not the path that
 * was actually called, so the limit below is never reached by normal traffic. It exists so that a
 * future change that starts tagging by a request value fails at the registry instead of in
 * Prometheus.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {

    @Bean
    fun denyDomainValueTags(): MeterFilter =
        MeterFilter.deny { id -> id.tags.any { tag -> tag.key in DOMAIN_TAG_KEYS } }

    @Bean
    fun limitRequestUriTagValues(): MeterFilter =
        MeterFilter.maximumAllowableTags(SERVER_REQUESTS, URI_TAG, MAX_URI_TAG_VALUES, MeterFilter.deny())

    private companion object {
        const val SERVER_REQUESTS = "http.server.requests"
        const val URI_TAG = "uri"

        /** Hundreds of endpoints is already a lot; a fixed set of them is the point. */
        const val MAX_URI_TAG_VALUES = 200

        val DOMAIN_TAG_KEYS = setOf("coupon_id", "couponId", "merchant_id", "merchantId", "customer_id", "customerId", "ip")
    }
}
