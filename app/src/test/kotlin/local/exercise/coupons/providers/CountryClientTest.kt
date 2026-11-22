package local.exercise.coupons.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class CountryClientTest {

    private val clientBuilder: RestClient.Builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(clientBuilder).build()
    private val client = CountryClient(clientBuilder, "https://api.country.is")

    @Test
    fun `requests the ip path and returns a lowercase country`() {
        server.expect(requestTo("https://api.country.is/8.8.8.8"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"ip":"8.8.8.8","country":"US","city":"Mountain View","continent":"NA"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals("us", client.getByIp("8.8.8.8"))
        server.verify()
    }

    @Test
    fun `unresolvable ip is reported as a lookup failure`() {
        server.expect(requestTo("https://api.country.is/192.0.2.1"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":404,"message":"Not Found"}}"""),
            )

        val failure = assertThrows<CountryLookupException> { client.getByIp("192.0.2.1") }

        assertEquals("Country lookup for IP '192.0.2.1' failed with HTTP 404", failure.message)
        server.verify()
    }

    @Test
    fun `missing country is reported as a lookup failure`() {
        server.expect(requestTo("https://api.country.is/8.8.4.4"))
            .andRespond(
                withSuccess("""{"ip":"8.8.4.4","country":null}""", MediaType.APPLICATION_JSON),
            )

        assertThrows<CountryLookupException> { client.getByIp("8.8.4.4") }
        server.verify()
    }
}
