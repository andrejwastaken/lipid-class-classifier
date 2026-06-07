package mk.ukim.finki.lipidclassclassifier.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "prediction_results")
class PredictionResult(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    var job: AnalysisJob = AnalysisJob(),

    @Column(name = "predicted_class", nullable = false)
    var predictedClass: String = "",

    @Column(nullable = false)
    var probability: Double = 0.0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
