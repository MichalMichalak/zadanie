package local.exercise.coupons.testing

import local.exercise.coupons.providers.CountryLookupException
import local.exercise.coupons.providers.CountryProvider

/**
 * Test double for the country lookup.
 *
 * The answer is a constructor parameter, so a test states its world in one line:
 * `FakeCountryProvider("de")`, or `FakeCountryProvider(failure = CountryLookupException("boom"))`
 * for the outage. Both can be changed later on the same instance.
 */
class FakeCountryProvider(
    var country: String = "pl",
    var failure: CountryLookupException? = null,
) : CountryProvider {

    /** The ips the code under test asked about, in order. */
    val calls: MutableList<String> = mutableListOf()

    override fun getByIp(ip: String): String {
        calls += ip
        failure?.let { throw it }
        return country
    }
}
