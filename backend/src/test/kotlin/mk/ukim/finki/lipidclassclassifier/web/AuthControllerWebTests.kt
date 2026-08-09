package mk.ukim.finki.lipidclassclassifier.web

import mk.ukim.finki.lipidclassclassifier.auth.AuthController
import mk.ukim.finki.lipidclassclassifier.auth.AuthResponse
import mk.ukim.finki.lipidclassclassifier.auth.AuthService
import mk.ukim.finki.lipidclassclassifier.auth.JwtService
import mk.ukim.finki.lipidclassclassifier.auth.LoginRequest
import mk.ukim.finki.lipidclassclassifier.auth.RegisterRequest
import mk.ukim.finki.lipidclassclassifier.auth.UserResponse
import mk.ukim.finki.lipidclassclassifier.config.JwtAuthenticationFilter
import mk.ukim.finki.lipidclassclassifier.config.SecurityConfig
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import org.junit.jupiter.api.DisplayName
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web-layer integration tests for [AuthController], using the Spring MVC Test Framework
 * (MockMvc). The real security filter chain from [SecurityConfig] is loaded so that the
 * rule permitting all `/api/auth` requests is actually exercised; only the service layer
 * is mocked.
 */
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
@ActiveProfiles("test")
class AuthControllerWebTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var authService: AuthService

    // Collaborators of the real JwtAuthenticationFilter.
    @MockitoBean
    private lateinit var jwtService: JwtService

    @MockitoBean
    private lateinit var userRepository: AppUserRepository

    private val userId: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private fun authResponse() = AuthResponse(
        token = "signed-token",
        user = UserResponse(id = userId, email = "student@example.com"),
    )

    private fun body(email: String, password: String) =
        """{"email":"$email","password":"$password"}"""

    @Test
    fun `register returns 201 with the token and user`() {
        whenever(authService.register(any())).thenReturn(authResponse())

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "password123")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").value("signed-token"))
            .andExpect(jsonPath("$.user.id").value(userId.toString()))
            .andExpect(jsonPath("$.user.email").value("student@example.com"))
    }

    @Test
    fun `register with a duplicate email returns 409`() {
        whenever(authService.register(any()))
            .thenThrow(ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered"))

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("taken@example.com", "password123")),
        )
            .andExpect(status().isConflict)
    }

    @Test
    @DisplayName("@Valid rejects a malformed email with 400 before the service is called")
    fun `register with an invalid email returns 400`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email", "password123")),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("@Valid enforces the 8 character minimum password length")
    fun `register with a short password returns 400`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "short")),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `register with a malformed JSON body returns 400`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `login returns 200 with the token and user`() {
        whenever(authService.login(any())).thenReturn(authResponse())

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "password123")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("signed-token"))
            .andExpect(jsonPath("$.user.email").value("student@example.com"))
    }

    @Test
    fun `login with bad credentials returns 401`() {
        whenever(authService.login(any()))
            .thenThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"))

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "wrong-password")),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("login does not apply the registration password-length rule")
    fun `login with a short password reaches the service and returns 401`() {
        whenever(authService.login(any()))
            .thenThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"))

        // RegisterRequest and LoginRequest were once a single AuthRequest, so @Size(min = 8)
        // applied here too and this returned 400 without ever reaching AuthService.
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "short")),
        )
            .andExpect(status().isUnauthorized)

        verify(authService).login(any())
    }

    @Test
    @DisplayName("a whitespace-padded address is normalised, not rejected")
    fun `login with a whitespace-padded email reaches the service`() {
        whenever(authService.login(any())).thenReturn(authResponse())

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("  student@example.com  ", "password123")),
        )
            .andExpect(status().isOk)

        val captor = argumentCaptor<LoginRequest>()
        verify(authService).login(captor.capture())
        assertEquals("student@example.com", captor.lastValue.email)
    }

    @Test
    @DisplayName("a mixed-case address is lower-cased on the way in")
    fun `login with a mixed-case email reaches the service normalised`() {
        whenever(authService.login(any())).thenReturn(authResponse())

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("STUDENT@EXAMPLE.COM", "password123")),
        )
            .andExpect(status().isOk)

        val captor = argumentCaptor<LoginRequest>()
        verify(authService).login(captor.capture())
        assertEquals("student@example.com", captor.lastValue.email)
    }

    @Test
    @DisplayName("registration normalises the email the same way")
    fun `register with a padded mixed-case email reaches the service normalised`() {
        whenever(authService.register(any())).thenReturn(authResponse())

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("  STUDENT@Example.com  ", "password123")),
        )
            .andExpect(status().isCreated)

        val captor = argumentCaptor<RegisterRequest>()
        verify(authService).register(captor.capture())
        assertEquals("student@example.com", captor.lastValue.email)
    }

    @Test
    fun `an email that is only whitespace is still rejected with 400`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("   ", "password123")),
        )
            .andExpect(status().isBadRequest)

        verify(authService, never()).login(any())
    }

    @Test
    fun `login with an empty password is still rejected with 400`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "")),
        )
            .andExpect(status().isBadRequest)

        verify(authService, never()).login(any())
    }

    @Test
    @DisplayName("the auth endpoints are reachable without a bearer token")
    fun `auth endpoints are public`() {
        whenever(authService.login(any())).thenReturn(authResponse())

        // No Authorization header at all, and the request still reaches the controller.
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("student@example.com", "password123")),
        )
            .andExpect(status().isOk)
    }
}
