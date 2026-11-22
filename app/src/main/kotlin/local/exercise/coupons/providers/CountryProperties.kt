package local.exercise.coupons.providers

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Settings for the country lookup: where the API lives and how long its answers are cached. */
@ConfigurationProperties(prefix = "country")
data class CountryProperties(
    val api: Api = Api(),
    val cache: Cache = Cache(),
) {
    data class Api(val baseUrl: String = "https://api.country.is")

    data class Cache(val ttl: Duration = Duration.ofHours(1))
}
