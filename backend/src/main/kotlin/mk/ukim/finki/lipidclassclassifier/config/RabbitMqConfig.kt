package mk.ukim.finki.lipidclassclassifier.config

import org.springframework.amqp.core.Queue
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitMqConfig {
    @Bean
    fun mlJobsQueue(@Value("\${app.messaging.ml-jobs-queue}") queueName: String): Queue =
        Queue(queueName, true)
}
