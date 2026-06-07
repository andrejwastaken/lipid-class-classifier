package mk.ukim.finki.lipidclassclassifier.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface AnalysisJobRepository : JpaRepository<AnalysisJob, UUID> {
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<AnalysisJob>
}
