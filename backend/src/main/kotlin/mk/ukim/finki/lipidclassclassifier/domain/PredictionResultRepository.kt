package mk.ukim.finki.lipidclassclassifier.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface PredictionResultRepository : JpaRepository<PredictionResult, UUID> {
    fun findByJobId(jobId: UUID): Optional<PredictionResult>
}
