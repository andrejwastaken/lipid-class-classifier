package mk.ukim.finki.lipidclassclassifier.auth

import mk.ukim.finki.lipidclassclassifier.domain.AppUser
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {
    fun register(request: AuthRequest): AuthResponse {
        val normalizedEmail = request.email.trim().lowercase()
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered")
        }

        val user = userRepository.save(
            AppUser(
                email = normalizedEmail,
                passwordHash = requireNotNull(passwordEncoder.encode(request.password)),
            ),
        )
        return responseFor(user)
    }

    fun login(request: AuthRequest): AuthResponse {
        val normalizedEmail = request.email.trim().lowercase()
        val user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password") }

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }

        return responseFor(user)
    }

    private fun responseFor(user: AppUser): AuthResponse {
        val id = requireNotNull(user.id)
        return AuthResponse(
            token = jwtService.createToken(id, user.email),
            user = UserResponse(id = id, email = user.email),
        )
    }
}
