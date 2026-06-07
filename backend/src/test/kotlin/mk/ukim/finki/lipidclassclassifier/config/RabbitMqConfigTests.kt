package mk.ukim.finki.lipidclassclassifier.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mk.ukim.finki.lipidclassclassifier.messaging.MlJobMessage
import org.springframework.amqp.core.MessageProperties
import java.nio.charset.StandardCharsets

class RabbitMqConfigTests {
    @Test
    fun `declares durable ml jobs queue`() {
        val queue = RabbitMqConfig().mlJobsQueue("ml_jobs")

        assertEquals("ml_jobs", queue.name)
        assertTrue(queue.isDurable)
    }

    @Test
    fun `serializes ML job messages as stable JSON contract`() {
        val converter = RabbitMqConfig().rabbitMessageConverter()
        val message = MlJobMessage(
            job_id = "job-1",
            file_path = "/tmp/input.mzML",
            user_id = "user-1",
        )

        val amqpMessage = converter.toMessage(message, MessageProperties())
        val json = String(amqpMessage.body, StandardCharsets.UTF_8)

        assertTrue("\"job_id\":\"job-1\"" in json)
        assertTrue("\"file_path\":\"/tmp/input.mzML\"" in json)
        assertTrue("\"user_id\":\"user-1\"" in json)
    }
}
