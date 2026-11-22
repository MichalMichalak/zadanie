package local.exercise.coupons

import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MetricsConfigurationTest {

    private val configuration = MetricsConfiguration()
    private val registry = SimpleMeterRegistry().apply {
        config().meterFilter(configuration.denyDomainValueTags())
        config().meterFilter(configuration.limitRequestUriTagValues())
    }

    @Test
    fun aMappedRequestPatternIsAccepted() {
        timer("/api/coupons/use")

        assertNotNull(registry.find("http.server.requests").tag("uri", "/api/coupons/use").timer())
    }

    @Test
    fun aTagCarryingADomainValueIsRefused() {
        registry.counter("coupon.views", "coupon_id", "summer26").increment()

        assertNull(registry.find("coupon.views").counter())
    }

    @Test
    fun tooManyRequestUriValuesAreRefused() {
        repeat(200) { timer("/api/resource/$it/{id}") }
        timer("/api/resource/too-many/{id}")

        assertNull(registry.find("http.server.requests").tag("uri", "/api/resource/too-many/{id}").timer())
        assertNotNull(registry.find("http.server.requests").tag("uri", "/api/resource/0/{id}").timer())
    }

    private fun timer(uri: String) {
        Timer.builder("http.server.requests").tag("uri", uri).register(registry)
    }
}
