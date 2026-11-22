package local.exercise.coupons.providers

import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

class CountryClient(
    clientBuilder: RestClient.Builder,
    baseUrl: String,
) : CountryProvider {

    private val client: RestClient = clientBuilder
        .baseUrl(baseUrl)
        .build()

    override fun getByIp(ip: String): String {
        val body = try {
            client.get()
                .uri("/{ip}", ip)
                .retrieve()
                .body(CountryResponse::class.java)
        } catch (ex: RestClientResponseException) {
            throw CountryLookupException(
                "Country lookup for IP '$ip' failed with HTTP ${ex.statusCode.value()}", ex,
            )
        }

        // The API answers with uppercase ISO codes (`"US"`); the service works with lowercase
        // everywhere, so normalize at the boundary instead of at every use site.
        return body?.country?.lowercase()
            ?: throw CountryLookupException("Country lookup for IP '$ip' returned no country")
    }
}

data class CountryResponse(val country: String? = null)
