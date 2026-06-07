package mk.ukim.finki.lipidclassclassifier.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class AuthRequest(
    @field:Email
    @field:NotBlank
    val email: String,

    @field:Size(min = 8, max = 200)
    val password: String,
)

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
