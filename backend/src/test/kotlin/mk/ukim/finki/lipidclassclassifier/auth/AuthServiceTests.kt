package mk.ukim.finki.lipidclassclassifier.auth

import mk.ukim.finki.lipidclassclassifier.domain.AppUser
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [AuthService], with Mockito doubles for the repository, the password encoder
 * and the JWT service. The interesting behaviour here is email normalisation and the fact that
 * both login failure modes must be indistinguishable to a caller.
 */
class AuthServiceTests {

    private lateinit var userRepository: AppUserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtService: JwtService
    private lateinit var service: AuthService

    private val userId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val storedUser = AppUser(id = userId, email = "a@b.com", passwordHash = "hashed-secret")

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        passwordEncoder = mock()
        jwtService = mock()
        service = AuthService(userRepository, passwordEncoder, jwtService)

        whenever(jwtService.createToken(any(), any())).thenReturn("signed-token")
    }

    private fun request(email: String, password: String = "password123") = AuthRequest(email, password)

    private fun assertRejected(expectedStatus: HttpStatus, block: () -> Unit) {
        val exception = assertFailsWith<ResponseStatusException> { block() }
        assertEquals(expectedStatus, exception.statusCode)
    }

    @Test
    fun `registering an already known email is rejected with 409`() {
        whenever(userRepository.existsByEmail("a@b.com")).thenReturn(true)

        assertRejected(HttpStatus.CONFLICT) { service.register(request("a@b.com")) }

        verify(userRepository, never()).save(any<AppUser>())
    }

    @Test
    fun `registering stores the normalised email and the encoded password`() {
        whenever(userRepository.existsByEmail(any())).thenReturn(false)
        whenever(passwordEncoder.encode("password123")).thenReturn("hashed-secret")
        whenever(userRepository.save(any<AppUser>())).thenAnswer { invocation ->
            invocation.getArgument<AppUser>(0).also { it.id = userId }
        }

        val response = service.register(request("  A@B.com  "))

        val captor = argumentCaptor<AppUser>()
        verify(userRepository).save(captor.capture())
        assertEquals("a@b.com", captor.lastValue.email)
        assertEquals("hashed-secret", captor.lastValue.passwordHash)

        assertEquals("signed-token", response.token)
        assertEquals(userId, response.user.id)
        assertEquals("a@b.com", response.user.email)
    }

    @Test
    @DisplayName("the duplicate check runs against the normalised email, not the raw input")
    fun `duplicate detection normalises before looking up`() {
        whenever(userRepository.existsByEmail("a@b.com")).thenReturn(true)

        // Different surface form, same account.
        assertRejected(HttpStatus.CONFLICT) { service.register(request(" A@B.com ")) }

        verify(userRepository).existsByEmail("a@b.com")
    }

    @Test
    fun `unknown email is rejected with 401`() {
        whenever(userRepository.findByEmail(any())).thenReturn(Optional.empty())

        assertRejected(HttpStatus.UNAUTHORIZED) { service.login(request("nobody@example.com")) }

        verify(jwtService, never()).createToken(any(), any())
    }

    @Test
    fun `wrong password is rejected with 401`() {
        whenever(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(storedUser))
        whenever(passwordEncoder.matches(any(), any())).thenReturn(false)

        assertRejected(HttpStatus.UNAUTHORIZED) { service.login(request("a@b.com", "wrong-password")) }

        verify(jwtService, never()).createToken(any(), any())
    }

    @Test
    @DisplayName("both login failures return the same status and message, so neither leaks whether the account exists")
    fun `unknown email and wrong password are indistinguishable`() {
        whenever(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty())
        whenever(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(storedUser))
        whenever(passwordEncoder.matches(any(), any())).thenReturn(false)

        val unknownEmail = assertFailsWith<ResponseStatusException> {
            service.login(request("ghost@example.com"))
        }
        val wrongPassword = assertFailsWith<ResponseStatusException> {
            service.login(request("a@b.com", "wrong-password"))
        }

        assertEquals(unknownEmail.statusCode, wrongPassword.statusCode)
        assertEquals(unknownEmail.reason, wrongPassword.reason)
    }

    @Test
    fun `successful login issues a token for the stored account`() {
        whenever(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(storedUser))
        whenever(passwordEncoder.matches("password123", "hashed-secret")).thenReturn(true)

        val response = service.login(request("a@b.com"))

        assertEquals("signed-token", response.token)
        assertEquals(userId, response.user.id)
        assertEquals("a@b.com", response.user.email)
        verify(jwtService).createToken(eq(userId), eq("a@b.com"))
    }

    @Test
    @DisplayName("\" A@B.com \" and \"a@b.com\" resolve to the same account")
    fun `login normalises the email before lookup`() {
        whenever(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(storedUser))
        whenever(passwordEncoder.matches(any(), any())).thenReturn(true)

        val spaced = service.login(request("  A@B.com  "))
        val plain = service.login(request("a@b.com"))

        assertEquals(plain.user.id, spaced.user.id)
        // Never queried with the raw, unnormalised form.
        verify(userRepository, never()).findByEmail("  A@B.com  ")
    }
}
