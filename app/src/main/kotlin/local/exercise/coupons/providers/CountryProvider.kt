package local.exercise.coupons.providers

interface CountryProvider {
    /**
     * Resolves the country of [ip].
     *
     * Returns a lowercase ISO 3166-1 alpha-2 code (e.g. `us`); implementations normalize the
     * upstream representation so callers never have to care about case.
     */
    fun getByIp(ip: String): String
}

class CountryLookupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
