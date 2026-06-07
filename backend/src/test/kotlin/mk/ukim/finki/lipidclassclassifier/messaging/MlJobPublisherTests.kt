package mk.ukim.finki.lipidclassclassifier.messaging

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.amqp.rabbit.core.RabbitTemplate

class MlJobPublisherTests {
    private val message = MlJobMessage(
        job_id = "job-1",
        file_path = "/tmp/input.mzML",
        user_id = "user-1",
    )

    @Test
    fun `publishes ML job message to configured queue`() {
        val rabbitTemplate = mock(RabbitTemplate::class.java)
        val publisher = RabbitMlJobPublisher(rabbitTemplate, "ml_jobs", 1, 0)

        publisher.publish(message)

        verify(rabbitTemplate).convertAndSend("ml_jobs", message)
    }

    @Test
    fun `retries publish failures before surfacing the error`() {
        val rabbitTemplate = mock(RabbitTemplate::class.java)
        val publisher = RabbitMlJobPublisher(rabbitTemplate, "ml_jobs", 3, 0)
        doThrow(RuntimeException("rabbit unavailable"))
            .`when`(rabbitTemplate)
            .convertAndSend("ml_jobs", message)

        assertFailsWith<RuntimeException> {
            publisher.publish(message)
        }

        verify(rabbitTemplate, times(3)).convertAndSend("ml_jobs", message)
    }
}
