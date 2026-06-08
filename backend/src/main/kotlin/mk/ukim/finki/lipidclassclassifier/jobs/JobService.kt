package mk.ukim.finki.lipidclassclassifier.jobs

import mk.ukim.finki.lipidclassclassifier.domain.AnalysisJob
import mk.ukim.finki.lipidclassclassifier.domain.AnalysisJobRepository
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import mk.ukim.finki.lipidclassclassifier.domain.JobStatus
import mk.ukim.finki.lipidclassclassifier.domain.PredictionResultRepository
import mk.ukim.finki.lipidclassclassifier.messaging.MlJobMessage
import mk.ukim.finki.lipidclassclassifier.messaging.MlJobPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Service
class JobService(
    private val userRepository: AppUserRepository,
    private val jobRepository: AnalysisJobRepository,
    private val predictionResultRepository: PredictionResultRepository,
    private val mlJobPublisher: MlJobPublisher,
    @Value("\${app.upload-dir}") uploadDir: String,
) {
    private val uploadRoot: Path = Paths.get(uploadDir).toAbsolutePath().normalize()

    fun createUploadJob(userId: UUID, file: MultipartFile): UploadResponse {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty")
        }

        val originalFilename = StringUtils.cleanPath(file.originalFilename ?: "spectrum.mzML")
        if (!originalFilename.endsWith(".mzML", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .mzML uploads are supported")
        }

        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user was not found") }

        Files.createDirectories(uploadRoot)
        val storedFilename = "${UUID.randomUUID()}-$originalFilename"
        val storedPath = uploadRoot.resolve(storedFilename).normalize()
        if (!storedPath.startsWith(uploadRoot)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload filename")
        }
        file.transferTo(storedPath)

        val job = jobRepository.save(
            AnalysisJob(
                user = user,
                originalFilename = originalFilename,
                storedFilePath = storedPath.toString(),
                status = JobStatus.PENDING,
            ),
        )
        val jobId = requireNotNull(job.id)

        try {
            mlJobPublisher.publish(
                MlJobMessage(
                    job_id = jobId.toString(),
                    file_path = job.storedFilePath,
                    user_id = userId.toString(),
                ),
            )
        } catch (ex: RuntimeException) {
            job.status = JobStatus.FAILED
            job.errorMessage = "Failed to publish ML processing job"
            jobRepository.save(job)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to queue ML processing job", ex)
        }

        return UploadResponse(job_id = jobId, status = job.status)
    }

    @Transactional(readOnly = true)
    fun getJob(userId: UUID, jobId: UUID): JobResponse {
        val job = jobRepository.findByIdAndUserId(jobId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Job was not found") }
        val prediction = predictionResultRepository.findByJobId(jobId).orElse(null)
        return job.toResponse(prediction)
    }
}
