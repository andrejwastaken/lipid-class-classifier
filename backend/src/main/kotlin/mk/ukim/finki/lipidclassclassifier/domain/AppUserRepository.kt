package mk.ukim.finki.lipidclassclassifier.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByEmail(email: String): Optional<AppUser>

    fun existsByEmail(email: String): Boolean
}
