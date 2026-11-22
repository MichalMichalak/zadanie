package local.exercise.coupons.testing

import local.exercise.coupons.providers.CountryProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Puts a [FakeCountryProvider] in place of the real country lookup, so context tests never call
 * api.country.is.
 *
 * `testCountryProvider` is `@Primary` because [local.exercise.coupons.providers.CountryClient] is a
 * [CountryProvider] too, so the type alone does not identify the bean to replace.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeCountryConfiguration {

    @Bean
    fun fakeCountryProvider(): FakeCountryProvider = FakeCountryProvider()

    @Bean
    @Primary
    fun testCountryProvider(fake: FakeCountryProvider): CountryProvider = fake
}
