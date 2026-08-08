package mk.ukim.finki.lipidclassclassifier.web

import mk.ukim.finki.lipidclassclassifier.auth.JwtService
import mk.ukim.finki.lipidclassclassifier.auth.UserPrincipal
import mk.ukim.finki.lipidclassclassifier.config.JwtAuthenticationFilter
import mk.ukim.finki.lipidclassclassifier.config.SecurityConfig
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import mk.ukim.finki.lipidclassclassifier.domain.JobStatus
import mk.ukim.finki.lipidclassclassifier.jobs.JobController
import mk.ukim.finki.lipidclassclassifier.jobs.JobResponse
import mk.ukim.finki.lipidclassclassifier.jobs.JobService
import mk.ukim.finki.lipidclassclassifier.jobs.TopPrediction
import mk.ukim.finki.lipidclassclassifier.jobs.UploadResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

/**
 * Web-layer integration tests for [JobController] using MockMvc.
 *
 * The real [SecurityConfig] and [JwtAuthenticationFilter] are loaded, with only [JwtService]
 * and [AppUserRepository] mocked, so bearer-token handling is genuinely exercised rather than
 * bypassed with a test-only authentication.
 */
@WebMvcTest(JobController::class)
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
@ActiveProfiles("test")
class JobControllerWebTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var jobService: JobService

    @MockitoBean
    private lateinit var jwtService: JwtService

    @MockitoBean
    private lateinit var userRepository: AppUserRepository

    private val userId: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val jobId: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
    private val principal = UserPrincipal(userId, "student@example.com")

    private val validToken = "valid-token"

    @BeforeEach
    fun setUp() {
        // A valid bearer token resolves to a user that still exists.
        whenever(jwtService.parseToken(validToken)).thenReturn(principal)
        whenever(userRepository.existsById(userId)).thenReturn(true)
    }

    private fun spectrum(filename: String = "sample.mzML") =
        MockMultipartFile("file", filename, "application/octet-stream", "mzML payload".toByteArray())

    private fun jobResponse(status: JobStatus, predictedClass: String? = null) = JobResponse(
        job_id = jobId,
        status = status,
        original_filename = "sample.mzML",
        predicted_class = predictedClass,
        probability = predictedClass?.let { 0.91 },
        confidence_label = predictedClass?.let { "Confident" },
        top_predictions = predictedClass?.let { listOf(TopPrediction(it, 0.91)) } ?: emptyList(),
        model_version = predictedClass?.let { "random_forest" },
        error_message = null,
        created_at = Instant.parse("2026-08-08T10:00:00Z"),
        updated_at = Instant.parse("2026-08-08T10:00:05Z"),
    )

    @Test
    fun `upload without a token returns 401`() {
        mockMvc.perform(multipart("/api/jobs/upload").file(spectrum()))
            .andExpect(status().isUnauthorized)

        verify(jobService, never()).createUploadJob(any(), any())
    }

    @Test
    fun `polling a job without a token returns 401`() {
        mockMvc.perform(get("/api/jobs/{jobId}", jobId))
            .andExpect(status().isUnauthorized)

        verify(jobService, never()).getJob(any(), any())
    }

    @Test
    @DisplayName("a token that fails signature or expiry checks is refused")
    fun `unparseable token returns 401`() {
        whenever(jwtService.parseToken("garbage")).thenReturn(null)

        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", "Bearer garbage"))
            .andExpect(status().isUnauthorized)

        verify(jobService, never()).getJob(any(), any())
    }

    @Test
    @DisplayName("a well-formed token for a deleted account is refused")
    fun `token for a user that no longer exists returns 401`() {
        whenever(userRepository.existsById(userId)).thenReturn(false)

        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", "Bearer $validToken"))
            .andExpect(status().isUnauthorized)

        verify(jobService, never()).getJob(any(), any())
    }

    @Test
    fun `an Authorization header without the Bearer prefix returns 401`() {
        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", validToken))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `upload returns 202 with the job id and PENDING status`() {
        whenever(jobService.createUploadJob(eq(userId), any()))
            .thenReturn(UploadResponse(job_id = jobId, status = JobStatus.PENDING))

        mockMvc.perform(
            multipart("/api/jobs/upload")
                .file(spectrum())
                .header("Authorization", "Bearer $validToken"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.job_id").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    @DisplayName("the principal from the token, not the request, decides the owner")
    fun `upload passes the authenticated user id to the service`() {
        whenever(jobService.createUploadJob(eq(userId), any()))
            .thenReturn(UploadResponse(job_id = jobId, status = JobStatus.PENDING))

        mockMvc.perform(
            multipart("/api/jobs/upload")
                .file(spectrum())
                .header("Authorization", "Bearer $validToken"),
        )
            .andExpect(status().isAccepted)

        verify(jobService).createUploadJob(eq(userId), any())
    }

    @Test
    fun `upload propagates a rejected file as 400`() {
        whenever(jobService.createUploadJob(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .mzML uploads are supported"))

        mockMvc.perform(
            multipart("/api/jobs/upload")
                .file(spectrum("sample.txt"))
                .header("Authorization", "Bearer $validToken"),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `upload propagates a queue failure as 503`() {
        whenever(jobService.createUploadJob(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to queue ML processing job"))

        mockMvc.perform(
            multipart("/api/jobs/upload")
                .file(spectrum())
                .header("Authorization", "Bearer $validToken"),
        )
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `upload without the file part returns 400`() {
        mockMvc.perform(
            multipart("/api/jobs/upload").header("Authorization", "Bearer $validToken"),
        )
            .andExpect(status().isBadRequest)

        verify(jobService, never()).createUploadJob(any(), any())
    }

    @Test
    fun `polling a pending job returns 200 with null prediction fields`() {
        whenever(jobService.getJob(userId, jobId)).thenReturn(jobResponse(JobStatus.PENDING))

        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", "Bearer $validToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.predicted_class").doesNotExist())
            .andExpect(jsonPath("$.original_filename").value("sample.mzML"))
    }

    @Test
    fun `polling a finished job returns the prediction payload`() {
        whenever(jobService.getJob(userId, jobId)).thenReturn(jobResponse(JobStatus.DONE, "PC"))

        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", "Bearer $validToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.job_id").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.predicted_class").value("PC"))
            .andExpect(jsonPath("$.probability").value(0.91))
            .andExpect(jsonPath("$.confidence_label").value("Confident"))
            .andExpect(jsonPath("$.top_predictions[0].class_name").value("PC"))
            .andExpect(jsonPath("$.model_version").value("random_forest"))
    }

    @Test
    @DisplayName("another user's job is reported as 404, not 403")
    fun `polling a job owned by someone else returns 404`() {
        whenever(jobService.getJob(any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Job was not found"))

        mockMvc.perform(get("/api/jobs/{jobId}", jobId).header("Authorization", "Bearer $validToken"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `polling with a malformed job id returns 400`() {
        mockMvc.perform(get("/api/jobs/not-a-uuid").header("Authorization", "Bearer $validToken"))
            .andExpect(status().isBadRequest)

        verify(jobService, never()).getJob(any(), any())
    }
}
