package local.exercise.coupons.providers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.client.RestClient

/** Wires the country lookup: the API client, wrapped in its cache. */
@Configuration(proxyBeanMethods = false)
class CountryProviderConfiguration(private val properties: CountryProperties) {

    @Bean
    fun countryClient(restClientBuilder: RestClient.Builder): CountryClient =
        CountryClient(restClientBuilder, properties.api.baseUrl)

    @Bean
    fun countryProvider(countryClient: CountryClient, redis: StringRedisTemplate): CountryProvider =
        CachedCountryProvider(countryClient, redis, properties.cache.ttl)
}
