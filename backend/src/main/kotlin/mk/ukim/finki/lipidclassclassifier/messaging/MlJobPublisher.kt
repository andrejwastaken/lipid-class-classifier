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
    @Value("\${app.messaging.publish-max-attempts:3}") private val publishMaxAttempts: Int,
    @Value("\${app.messaging.publish-retry-backoff-ms:250}") private val publishRetryBackoffMs: Long,
) : MlJobPublisher {
    private val logger = LoggerFactory.getLogger(RabbitMlJobPublisher::class.java)

    override fun publish(message: MlJobMessage) {
        val attempts = publishMaxAttempts.coerceAtLeast(1)

        for (attempt in 1..attempts) {
            try {
                rabbitTemplate.convertAndSend(queueName, message)
                if (attempt > 1) {
                    logger.info("Published ML job ${message.job_id} to queue $queueName after $attempt attempts")
                }
                return
            } catch (ex: RuntimeException) {
                if (attempt == attempts) {
                    logger.error("Failed to publish ML job ${message.job_id} to queue $queueName after $attempt attempts", ex)
                    throw ex
                }
                logger.warn("Failed to publish ML job ${message.job_id} to queue $queueName on attempt $attempt; retrying", ex)
                waitBeforeRetry()
            }
        }
    }

    private fun waitBeforeRetry() {
        if (publishRetryBackoffMs <= 0) {
            return
        }

        try {
            Thread.sleep(publishRetryBackoffMs)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting to retry ML job publish", ex)
        }
    }
}
