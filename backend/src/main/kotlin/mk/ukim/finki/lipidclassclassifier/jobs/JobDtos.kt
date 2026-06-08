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
    val confidence_label: String?,
    val top_predictions: List<TopPrediction>,
    val model_version: String?,
    val error_message: String?,
    val created_at: Instant,
    val updated_at: Instant,
)

data class TopPrediction(
    val class_name: String,
    val probability: Double,
)

fun AnalysisJob.toResponse(prediction: PredictionResult?): JobResponse =
    JobResponse(
        job_id = requireNotNull(id),
        status = status,
        original_filename = originalFilename,
        predicted_class = prediction?.predictedClass,
        probability = prediction?.probability,
        confidence_label = prediction?.let { if (it.probability < 0.20) "Low confidence" else "Confident" },
        top_predictions = prediction?.topPredictions() ?: emptyList(),
        model_version = prediction?.modelVersion,
        error_message = errorMessage,
        created_at = createdAt,
        updated_at = updatedAt,
    )

private fun PredictionResult.topPredictions(): List<TopPrediction> {
    val classes = topPredictedClasses
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    val probabilities = topProbabilities
        ?.split(",")
        ?.mapNotNull { it.trim().toDoubleOrNull() }
        ?: emptyList()

    val parsed = classes.zip(probabilities).map { (className, probability) ->
        TopPrediction(class_name = className, probability = probability)
    }
    if (parsed.isNotEmpty()) {
        return parsed
    }

    return listOf(TopPrediction(class_name = predictedClass, probability = probability))
}
