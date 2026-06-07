package mk.ukim.finki.lipidclassclassifier.jobs

import mk.ukim.finki.lipidclassclassifier.domain.AnalysisJob
import mk.ukim.finki.lipidclassclassifier.domain.JobStatus
import mk.ukim.finki.lipidclassclassifier.domain.PredictionResult
import java.time.Instant
import java.util.UUID

data class UploadResponse(
    val job_id: UUID,
    val status: JobStatus,
)

data class JobResponse(
    val job_id: UUID,
    val status: JobStatus,
    val original_filename: String,
    val predicted_class: String?,
    val probability: Double?,
    val model_version: String?,
    val error_message: String?,
    val created_at: Instant,
    val updated_at: Instant,
)

fun AnalysisJob.toResponse(prediction: PredictionResult?): JobResponse =
    JobResponse(
        job_id = requireNotNull(id),
        status = status,
        original_filename = originalFilename,
        predicted_class = prediction?.predictedClass,
        probability = prediction?.probability,
        model_version = prediction?.modelVersion,
        error_message = errorMessage,
        created_at = createdAt,
        updated_at = updatedAt,
    )
