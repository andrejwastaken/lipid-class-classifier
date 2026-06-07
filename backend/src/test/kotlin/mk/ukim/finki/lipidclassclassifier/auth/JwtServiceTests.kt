package mk.ukim.finki.lipidclassclassifier.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import tools.jackson.databind.ObjectMapper
import java.util.UUID

class JwtServiceTests {
    private val objectMapper = ObjectMapper()

    @Test
    fun `created token parses back to the same user`() {
        val service = JwtService(
            objectMapper = objectMapper,
            secret = "test-secret-with-enough-length-for-local-tests",
            expirationMs = 60_000,
        )
        val userId = UUID.randomUUID()

        val principal = service.parseToken(service.createToken(userId, "user@example.com"))

        assertNotNull(principal)
        assertEquals(userId, principal.id)
        assertEquals("user@example.com", principal.email)
    }
}
