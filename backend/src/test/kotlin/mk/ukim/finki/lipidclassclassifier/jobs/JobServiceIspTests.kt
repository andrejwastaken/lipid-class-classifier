package mk.ukim.finki.lipidclassclassifier.jobs

import mk.ukim.finki.lipidclassclassifier.domain.AnalysisJob
import mk.ukim.finki.lipidclassclassifier.domain.AnalysisJobRepository
import mk.ukim.finki.lipidclassclassifier.domain.AppUser
import mk.ukim.finki.lipidclassclassifier.domain.AppUserRepository
import mk.ukim.finki.lipidclassclassifier.domain.JobStatus
import mk.ukim.finki.lipidclassclassifier.domain.PredictionResultRepository
import mk.ukim.finki.lipidclassclassifier.messaging.MlJobMessage
import mk.ukim.finki.lipidclassclassifier.messaging.MlJobPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Input Space Partitioning applied to [JobService.createUploadJob].
 *
 * Characteristics and blocks (see plan.md):
 *
 *  C1  File content        empty (0 bytes) | non-empty
 *  C2  Original filename   null | "" | valid .mzML | .MZML | .mzml |
 *                          wrong ext (.mzXML, .txt) | "mzML" with no dot | sample.mzML.gz
 *  C3  Authenticated user  exists in DB | does not exist
 *  C4  Publish outcome     succeeds | throws RuntimeException
 *
 * Coverage criterion: **Base Choice Coverage**. The base choice is
 * (non-empty, "sample.mzML", user exists, publish succeeds) - the ordinary successful upload.
 * Every other test varies exactly one characteristic away from that base choice.
 */
class JobServiceIspTests {

    private lateinit var userRepository: AppUserRepository
    private lateinit var jobRepository: AnalysisJobRepository
    private lateinit var predictionResultRepository: PredictionResultRepository
    private lateinit var publisher: MlJobPublisher
    private lateinit var service: JobService
    private lateinit var uploadRoot: Path

    private val userId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val jobId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val user = AppUser(id = userId, email = "student@example.com", passwordHash = "hash")

    /** Base choice for C1: a few bytes of content, so `file.isEmpty` is false. */
    private val spectrumBytes = "mzML spectrum payload".toByteArray()

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        uploadRoot = tempDir
        userRepository = mock()
        jobRepository = mock()
        predictionResultRepository = mock()
        publisher = mock()
        service = JobService(
            userRepository = userRepository,
            jobRepository = jobRepository,
            predictionResultRepository = predictionResultRepository,
            mlJobPublisher = publisher,
            uploadDir = tempDir.toString(),
        )

        // C3 base choice: the authenticated user exists.
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        // The repository assigns the primary key, so the double does too.
        whenever(jobRepository.save(any<AnalysisJob>())).thenAnswer { invocation ->
            val saved = invocation.getArgument<AnalysisJob>(0)
            if (saved.id == null) {
                saved.id = jobId
            }
            saved
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun upload(file: MultipartFile): UploadResponse = service.createUploadJob(userId, file)

    private fun file(originalFilename: String?, content: ByteArray = spectrumBytes): MockMultipartFile =
        MockMultipartFile("file", originalFilename, "application/octet-stream", content)

    private fun savedJob(): AnalysisJob {
        val captor = argumentCaptor<AnalysisJob>()
        verify(jobRepository, times(1)).save(captor.capture())
        return captor.lastValue
    }

    private fun publishedMessage(): MlJobMessage {
        val captor = argumentCaptor<MlJobMessage>()
        verify(publisher, times(1)).publish(captor.capture())
        return captor.lastValue
    }

    /** Asserts the whole happy-path outcome: accepted response, PENDING job, exactly one message. */
    private fun assertAcceptedUpload(response: UploadResponse, expectedFilename: String) {
        assertEquals(jobId, response.job_id)
        assertEquals(JobStatus.PENDING, response.status)

        val job = savedJob()
        assertEquals(expectedFilename, job.originalFilename)
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(user, job.user)

        val message = publishedMessage()
        assertEquals(jobId.toString(), message.job_id)
        assertEquals(userId.toString(), message.user_id)
        assertEquals(job.storedFilePath, message.file_path)
    }

    private fun assertRejected(expectedStatus: HttpStatus, block: () -> Unit): ResponseStatusException {
        val exception = assertFailsWith<ResponseStatusException>(block = block)
        assertEquals(expectedStatus, exception.statusCode)
        return exception
    }

    // ------------------------------------------------------------ base choice

    @Test
    @DisplayName("base choice: non-empty .mzML from a known user, publish succeeds -> PENDING job")
    fun `base choice is accepted and published exactly once`() {
        val response = upload(file("sample.mzML"))

        assertAcceptedUpload(response, "sample.mzML")

        // The upload really lands under the configured upload directory.
        val stored = Path.of(savedJob().storedFilePath)
        assertTrue(stored.startsWith(uploadRoot), "stored file must live under the upload root")
        assertTrue(Files.exists(stored), "the uploaded file must be written to disk")
        assertEquals(spectrumBytes.toList(), Files.readAllBytes(stored).toList())
    }

    // ------------------------------------------------------- C1: file content

    @Nested
    @DisplayName("C1 file content")
    inner class FileContent {

        @Test
        fun `empty file is rejected with 400 before anything is persisted`() {
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("sample.mzML", ByteArray(0))) }

            verify(jobRepository, never()).save(any<AnalysisJob>())
            verify(publisher, never()).publish(any())
        }
    }

    // --------------------------------------------------- C2: original filename

    @Nested
    @DisplayName("C2 original filename")
    inner class OriginalFilename {

        @Test
        @DisplayName("FINDING: a null filename is silently accepted and defaults to spectrum.mzML")
        fun `null filename defaults to spectrum mzML`() {
            // MockMultipartFile normalises a null original filename to "", so the null block
            // is only reachable through a hand-rolled double.
            val nullNamed = mock<MultipartFile>()
            whenever(nullNamed.isEmpty).thenReturn(false)
            whenever(nullNamed.originalFilename).thenReturn(null)

            val response = upload(nullNamed)

            assertAcceptedUpload(response, "spectrum.mzML")
        }

        @Test
        fun `blank filename is rejected with 400`() {
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("")) }

            verify(publisher, never()).publish(any())
        }

        @Test
        fun `uppercase MZML extension is accepted`() {
            assertAcceptedUpload(upload(file("sample.MZML")), "sample.MZML")
        }

        @Test
        fun `lowercase mzml extension is accepted`() {
            assertAcceptedUpload(upload(file("sample.mzml")), "sample.mzml")
        }

        @Test
        fun `mzXML extension is rejected with 400`() {
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("sample.mzXML")) }

            verify(jobRepository, never()).save(any<AnalysisJob>())
            verify(publisher, never()).publish(any())
        }

        @Test
        fun `txt extension is rejected with 400`() {
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("sample.txt")) }

            verify(publisher, never()).publish(any())
        }

        @Test
        @DisplayName("a dotless \"mzML\" is rejected - the guard requires the dot")
        fun `filename without a dot is rejected with 400`() {
            // plan.md predicted this block would pass through the plain `endsWith`. It does not:
            // the suffix being matched is ".mzML" (5 chars), so the 4-char "mzML" cannot end
            // with it. The prediction was wrong; the guard behaves correctly here.
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("mzML")) }

            verify(publisher, never()).publish(any())
        }

        @Test
        @DisplayName("FINDING: a bare \".mzML\" with no basename is accepted")
        fun `extension-only filename is accepted`() {
            // This is what the plain `endsWith` really lets through: a file with no name at all,
            // which is stored as "<uuid>-.mzML".
            assertAcceptedUpload(upload(file(".mzML")), ".mzML")
        }

        @Test
        fun `double extension sample mzML gz is rejected with 400`() {
            assertRejected(HttpStatus.BAD_REQUEST) { upload(file("sample.mzML.gz")) }

            verify(publisher, never()).publish(any())
        }

        @Test
        @DisplayName("INFEASIBLE BLOCK: the path-traversal guard cannot be reached")
        fun `traversal in the filename never escapes the upload root`() {
            // The stored name is always "<random uuid>-<cleaned filename>", so the first path
            // segment can never itself be "..". Normalisation therefore cannot climb above the
            // upload root, and `if (!storedPath.startsWith(uploadRoot))` is dead code.
            val response = upload(file("../../evil.mzML"))

            assertEquals(JobStatus.PENDING, response.status)
            val stored = Path.of(savedJob().storedFilePath)
            assertTrue(stored.startsWith(uploadRoot), "traversal was absorbed by the uuid prefix")
        }
    }

    // -------------------------------------------------- C3: authenticated user

    @Nested
    @DisplayName("C3 authenticated user")
    inner class AuthenticatedUser {

        @Test
        fun `unknown user is rejected with 401 and no job is created`() {
            whenever(userRepository.findById(userId)).thenReturn(Optional.empty())

            assertRejected(HttpStatus.UNAUTHORIZED) { upload(file("sample.mzML")) }

            verify(jobRepository, never()).save(any<AnalysisJob>())
            verify(publisher, never()).publish(any())
        }
    }

    // ------------------------------------------------------ C4: publish outcome

    @Nested
    @DisplayName("C4 queue publish outcome")
    inner class PublishOutcome {

        @BeforeEach
        fun publishAlwaysFails() {
            whenever(publisher.publish(any())).thenThrow(RuntimeException("rabbit unavailable"))
        }

        @Test
        fun `publish failure returns 503 and flips the job to FAILED`() {
            assertRejected(HttpStatus.SERVICE_UNAVAILABLE) { upload(file("sample.mzML")) }

            // Saved twice: once as PENDING, then again after the failure is recorded.
            val captor = argumentCaptor<AnalysisJob>()
            verify(jobRepository, times(2)).save(captor.capture())
            val failed = captor.lastValue
            assertEquals(JobStatus.FAILED, failed.status)
            assertEquals("Failed to publish ML processing job", failed.errorMessage)
        }

        @Test
        @DisplayName("FINDING: a failed publish leaves the uploaded file orphaned on disk")
        fun `publish failure does not delete the stored upload`() {
            assertRejected(HttpStatus.SERVICE_UNAVAILABLE) { upload(file("sample.mzML")) }

            val orphans = Files.list(uploadRoot).use { it.toList() }
            assertEquals(1, orphans.size, "the stored upload is never cleaned up")
            assertTrue(orphans.single().fileName.toString().endsWith("-sample.mzML"))
        }
    }
}
