package mk.ukim.finki.lipidclassclassifier.config

import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitMqConfig {
    @Bean
    fun mlJobsQueue(@Value("\${app.messaging.ml-jobs-queue}") queueName: String): Queue =
        Queue(queueName, true)

    @Bean
    fun rabbitMessageConverter(): MessageConverter =
        JacksonJsonMessageConverter()

    @Bean
    fun rabbitTemplate(
        connectionFactory: ConnectionFactory,
        rabbitMessageConverter: MessageConverter,
    ): RabbitTemplate =
        RabbitTemplate(connectionFactory).apply {
            messageConverter = rabbitMessageConverter
        }
}
