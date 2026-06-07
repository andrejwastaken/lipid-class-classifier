package mk.ukim.finki.lipidclassclassifier.messaging

import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

data class MlJobMessage(
    val job_id: String,
    val file_path: String,
    val user_id: String,
)

interface MlJobPublisher {
    fun publish(message: MlJobMessage)
}

@Component
class RabbitMlJobPublisher(
    private val rabbitTemplate: RabbitTemplate,
    @Value("\${app.messaging.ml-jobs-queue}") private val queueName: String,
) : MlJobPublisher {
    private val logger = LoggerFactory.getLogger(RabbitMlJobPublisher::class.java)

    override fun publish(message: MlJobMessage) {
        try {
            rabbitTemplate.convertAndSend(queueName, message)
        } catch (ex: RuntimeException) {
            logger.error("Failed to publish ML job ${message.job_id} to queue $queueName", ex)
            throw ex
        }
    }
}
