package local.exercise.coupons

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import local.exercise.coupons.testing.TestContainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The management connector gets a random port too, so the tests never depend on 8081 being free.
    properties = ["management.server.port=0"],
)
@Import(TestContainersConfiguration::class)
class ApplicationTests {

    @LocalServerPort
    private var port: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    @Test
    fun healthEndpointIsExposed() {
        assertEquals(200, get(managementPort, "/actuator/health").statusCode())
    }

    @Test
    fun metricsAreServedInPrometheusFormat() {
        // A request on the application port first, so the scrape has a request series to show. The
        // management connector records its own requests separately, so the tagged one has to come
        // from the API.
        post(port, "/api/coupons")

        val response = get(managementPort, "/actuator/prometheus")

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        val body = response.body()
        assertTrue(body.contains("jvm_memory_used_bytes"), "the JVM metrics are missing")
        assertTrue(
            body.contains("uri=\"/api/coupons\""),
            "request metrics must be tagged with the mapped pattern",
        )
    }

    @Test
    fun endpointsOutsideTheExposureListAreNotServed() {
        assertEquals(404, get(managementPort, "/actuator/env").statusCode())
    }

    @Test
    fun theApplicationPortServesNoManagementEndpoints() {
        // Management runs on its own connector, so a public ingress that forwards everything to the
        // application port still cannot reach the metrics.
        assertEquals(404, get(port, "/actuator/prometheus").statusCode())
        assertEquals(404, get(port, "/actuator/health").statusCode())
    }

    private fun post(targetPort: Int, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$targetPort$path"))
            .header("Content-Type", "application/json")
            .header("m_id", "1")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(targetPort: Int, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$targetPort$path"))
            .GET()
            .build()

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }
}
