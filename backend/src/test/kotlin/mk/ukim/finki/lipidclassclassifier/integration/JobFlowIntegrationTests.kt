package mk.ukim.finki.lipidclassclassifier.integration

import org.junit.jupiter.api.DisplayName
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for the job flow, running the whole Spring context against a
 * real PostgreSQL 17 and a real RabbitMQ started by Testcontainers and wired in through
 * `@ServiceConnection`. Nothing here is mocked.
 *
 * The ML worker itself is not run; instead the test executes the worker's own SQL directly,
 * which also checks that the Python worker's statements still match the JPA-generated schema.
 *
 * Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class JobFlowIntegrationTests {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17")

        @Container
        @ServiceConnection
        @JvmStatic
        val rabbitmq: RabbitMQContainer = RabbitMQContainer("rabbitmq:3-management")

        private val uploadRoot: Path = Files.createTempDirectory("lipid-integration-uploads")

        @JvmStatic
        @DynamicPropertySource
        fun testProperties(registry: DynamicPropertyRegistry) {
            // Keep uploads out of the working tree.
            registry.add("app.upload-dir") { uploadRoot.toString() }
        }
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Value("\${app.messaging.ml-jobs-queue}")
    private lateinit var queueName: String

    // Registers a fresh account and returns its bearer token. 
    private fun register(email: String = "user-${UUID.randomUUID()}@example.com"): String {
        val response = restTemplate.postForEntity(
            "/api/auth/register",
            jsonEntity("""{"email":"$email","password":"password123"}"""),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return assertNotNull(response.body)["token"] as String
    }

    private fun jsonEntity(body: String): HttpEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }

    private fun uploadEntity(token: String, filename: String): HttpEntity<LinkedMultiValueMap<String, Any>> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        headers.setBearerAuth(token)

        val file = object : ByteArrayResource("mzML spectrum payload".toByteArray()) {
            override fun getFilename(): String = filename
        }
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", file)
        return HttpEntity(body, body.let { headers })
    }

    private fun upload(token: String, filename: String = "sample.mzML") =
        restTemplate.postForEntity("/api/jobs/upload", uploadEntity(token, filename), Map::class.java)

    private fun pollJob(token: String, jobId: String) =
        restTemplate.exchange(
            "/api/jobs/$jobId",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(token) }),
            Map::class.java,
        )

    // Drains one message from the ml_jobs queue and returns its raw JSON body as a map. 
    private fun receiveMlJob(): Map<*, *> {
        val message = rabbitTemplate.receive(queueName, 5_000)
        assertNotNull(message, "expected a message on the $queueName queue")
        return objectMapper.readValue(String(message.body, Charsets.UTF_8), Map::class.java)
    }

    /**
     * Replays what `worker.py::write_prediction_result` does for a DONE result. The SQL text is
     * copied from the worker; only the parameter binding differs, since JDBC needs real UUIDs
     * where psycopg accepts strings.
     */
    private fun applyWorkerDoneSql(jobId: String, predictedClass: String, probability: Double) {
        jdbcTemplate.update(
            "UPDATE analysis_jobs SET status = ?, error_message = NULL, updated_at = now() WHERE id = ?",
            "PROCESSING",
            UUID.fromString(jobId),
        )
        jdbcTemplate.update(
            """
            INSERT INTO prediction_results
                (id, job_id, predicted_class, probability, model_version,
                 top_predicted_classes, top_probabilities, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.fromString(jobId),
            predictedClass,
            probability,
            "random_forest",
            "$predictedClass,PE",
            "$probability,0.04",
        )
        jdbcTemplate.update(
            "UPDATE analysis_jobs SET status = ?, error_message = NULL, updated_at = now() WHERE id = ?",
            "DONE",
            UUID.fromString(jobId),
        )
    }

    private fun jobStatusInDatabase(jobId: String): String =
        jdbcTemplate.queryForObject(
            "SELECT status FROM analysis_jobs WHERE id = ?",
            String::class.java,
            UUID.fromString(jobId),
        )!!

    @Test
    @DisplayName("register -> upload -> queue -> worker SQL -> poll, against real Postgres and RabbitMQ")
    fun `the whole job flow works end to end`() {
        val token = register()

        val uploadResponse = upload(token)
        assertEquals(HttpStatus.ACCEPTED, uploadResponse.statusCode)
        val uploadBody = assertNotNull(uploadResponse.body)
        val jobId = uploadBody["job_id"] as String
        assertEquals("PENDING", uploadBody["status"])

        // A real row exists in PostgreSQL.
        val row = jdbcTemplate.queryForMap(
            "SELECT status, original_filename, stored_file_path FROM analysis_jobs WHERE id = ?",
            UUID.fromString(jobId),
        )
        assertEquals("PENDING", row["status"])
        assertEquals("sample.mzML", row["original_filename"])

        // The uploaded file really landed on disk.
        val storedPath = Path.of(row["stored_file_path"] as String)
        assertTrue(Files.exists(storedPath), "the stored upload should exist at $storedPath")

        // A real message is sitting on the ml_jobs queue, matching the documented contract.
        val message = receiveMlJob()
        assertEquals(setOf("job_id", "file_path", "user_id"), message.keys)
        assertEquals(jobId, message["job_id"])
        assertEquals(row["stored_file_path"], message["file_path"])

        // Polling before the worker has run still reports PENDING with no prediction.
        val pending = pollJob(token, jobId)
        assertEquals(HttpStatus.OK, pending.statusCode)
        assertEquals("PENDING", assertNotNull(pending.body)["status"])

        // Now let the worker's SQL run, and poll again.
        applyWorkerDoneSql(jobId, "PC", 0.91)

        val done = pollJob(token, jobId)
        assertEquals(HttpStatus.OK, done.statusCode)
        val doneBody = assertNotNull(done.body)
        assertEquals("DONE", doneBody["status"])
        assertEquals("PC", doneBody["predicted_class"])
        assertEquals(0.91, doneBody["probability"])
        assertEquals("Confident", doneBody["confidence_label"])
        assertEquals("random_forest", doneBody["model_version"])

        @Suppress("UNCHECKED_CAST")
        val topPredictions = doneBody["top_predictions"] as List<Map<String, Any>>
        assertEquals("PC", topPredictions.first()["class_name"])
    }

    @Test
    fun `a token issued by login authorises uploads just like a registration token`() {
        val email = "login-${UUID.randomUUID()}@example.com"
        register(email)

        val loginResponse = restTemplate.postForEntity(
            "/api/auth/login",
            jsonEntity("""{"email":"$email","password":"password123"}"""),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, loginResponse.statusCode)
        val loginToken = assertNotNull(loginResponse.body)["token"] as String

        assertEquals(HttpStatus.ACCEPTED, upload(loginToken).statusCode)
        receiveMlJob()
    }

    @Test
    @DisplayName("one user cannot read another user's job")
    fun `a job belonging to another user is reported as 404`() {
        val owner = register()
        val jobId = assertNotNull(upload(owner).body)["job_id"] as String
        receiveMlJob()

        val stranger = register()

        val response = pollJob(stranger, jobId)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        // The job is untouched and still readable by its owner.
        assertEquals(HttpStatus.OK, pollJob(owner, jobId).statusCode)
    }

    @Test
    fun `uploading without a token returns 401 and stores nothing`() {
        val before = jdbcTemplate.queryForObject("SELECT count(*) FROM analysis_jobs", Long::class.java)!!

        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource("payload".toByteArray()) {
            override fun getFilename(): String = "sample.mzML"
        })

        val response = restTemplate.postForEntity("/api/jobs/upload", HttpEntity(body, headers), String::class.java)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals(before, jdbcTemplate.queryForObject("SELECT count(*) FROM analysis_jobs", Long::class.java))
    }

    @Test
    fun `a wrong extension is rejected with 400 and creates no job row`() {
        val token = register()
        val before = jdbcTemplate.queryForObject("SELECT count(*) FROM analysis_jobs", Long::class.java)!!

        val response = upload(token, "wrong-extension.txt")

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(before, jdbcTemplate.queryForObject("SELECT count(*) FROM analysis_jobs", Long::class.java))
    }

    @Test
    fun `registering the same email twice returns 409`() {
        val email = "duplicate-${UUID.randomUUID()}@example.com"
        register(email)

        val response = restTemplate.postForEntity(
            "/api/auth/register",
            jsonEntity("""{"email":"$email","password":"password123"}"""),
            String::class.java,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    @DisplayName("the job row is owned by the authenticated user, and the queue message agrees")
    fun `the published message carries the owning user id`() {
        val token = register()
        val jobId = assertNotNull(upload(token).body)["job_id"] as String

        val ownerId = jdbcTemplate.queryForObject(
            "SELECT user_id FROM analysis_jobs WHERE id = ?",
            UUID::class.java,
            UUID.fromString(jobId),
        )

        assertEquals(ownerId.toString(), receiveMlJob()["user_id"])
    }

    @Test
    fun `the job status written by the worker SQL is visible in the database`() {
        val token = register()
        val jobId = assertNotNull(upload(token).body)["job_id"] as String
        receiveMlJob()

        assertEquals("PENDING", jobStatusInDatabase(jobId))
        applyWorkerDoneSql(jobId, "TG", 0.55)
        assertEquals("DONE", jobStatusInDatabase(jobId))
    }
}
