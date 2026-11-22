package local.exercise.coupons.providers

import java.time.Duration
import local.exercise.coupons.testing.FakeCountryProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

class CachedCountryProviderTest {

    @Suppress("UNCHECKED_CAST")
    private val values: ValueOperations<String, String> =
        mock(ValueOperations::class.java) as ValueOperations<String, String>

    private val redis: StringRedisTemplate = mock(StringRedisTemplate::class.java).also {
        `when`(it.opsForValue()).thenReturn(values)
    }

    private val delegate = FakeCountryProvider()

    private val ttl: Duration = Duration.ofHours(1)

    private val provider = CachedCountryProvider(delegate, redis, ttl)

    @Test
    fun `cache miss looks the ip up and stores the country under the network key`() {
        `when`(values.get("cc_8.8.8")).thenReturn(null)

        assertEquals("pl", provider.getByIp("8.8.8.8"))

        assertEquals(listOf("8.8.8.8"), delegate.calls)
        verify(values).set("cc_8.8.8", "pl", ttl)
    }

    @Test
    fun `cache hit does not call the api`() {
        `when`(values.get("cc_8.8.8")).thenReturn("pl")

        assertEquals("pl", provider.getByIp("8.8.8.123"))

        assertEquals(emptyList<String>(), delegate.calls)
        verify(values, never()).set(anyString(), anyString(), any(Duration::class.java))
    }

    @Test
    fun `reduces an ipv4 address to its network`() {
        assertEquals("8.8.8", CachedCountryProvider.networkOf("8.8.8.8"))
        assertEquals("127.0.0", CachedCountryProvider.networkOf("127.0.0.1"))
        assertEquals("192.168.31", CachedCountryProvider.networkOf("192.168.31.77"))
    }

    @Test
    fun `rejects addresses that are not ipv4`() {
        assertThrows<CountryLookupException> { CachedCountryProvider.networkOf("::1") }
        assertThrows<CountryLookupException> { CachedCountryProvider.networkOf("1.2.3") }
        assertThrows<CountryLookupException> { CachedCountryProvider.networkOf("1.2.3.400") }
        assertThrows<CountryLookupException> { CachedCountryProvider.networkOf("") }
    }
}
