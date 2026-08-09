package mk.ukim.finki.lipidclassclassifier.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

class RegisterRequest(
    email: String,

    @field:NotBlank
    @field:Size(min = 8, max = 200)
    val password: String,
) {
    @field:Email
    @field:NotBlank
    val email: String = normalizeEmail(email)
}

class LoginRequest(
    email: String,

    @field:NotBlank
    val password: String,
) {
    @field:Email
    @field:NotBlank
    val email: String = normalizeEmail(email)
}

internal fun normalizeEmail(value: String): String = value.trim().lowercase()

data class AuthResponse(
    val token: String,
    val user: UserResponse,
)

data class UserResponse(
    val id: UUID,
    val email: String,
)

data class UserPrincipal(
    val id: UUID,
    val email: String,
)
