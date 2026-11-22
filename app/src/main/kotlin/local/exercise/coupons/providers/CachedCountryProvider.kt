package local.exercise.coupons.providers

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Caches country lookups in Redis.
 *
 * The whole IPv4 /24 network is assumed to be in one country, so the cache key is the network
 * (`A.B.C`) rather than the full address: `cc_8.8.8`. Values are cached as the provider returned
 * them, which is always a lowercase ISO code.
 */
class CachedCountryProvider(
    private val delegate: CountryProvider,
    private val redis: StringRedisTemplate,
    private val ttl: Duration,
) : CountryProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getByIp(ip: String): String {
        val key = CACHE_KEY_PREFIX + networkOf(ip)

        redis.opsForValue().get(key)?.let {
            log.debug("Country cache hit for {}", key)
            return it
        }

        val country = delegate.getByIp(ip)
        redis.opsForValue().set(key, country, ttl)
        return country
    }

    companion object {
        const val CACHE_KEY_PREFIX = "cc_"

        /** Reduces an IPv4 address to its /24 network, e.g. `8.8.8.8` -> `8.8.8`. */
        fun networkOf(ip: String): String {
            val octets = ip.trim().split('.')
            val valid = octets.size == 4 &&
                    octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
            if (!valid) {
                throw CountryLookupException("Only IPv4 addresses are supported, got '$ip'")
            }
            return octets.take(3).joinToString(".")
        }
    }
}
