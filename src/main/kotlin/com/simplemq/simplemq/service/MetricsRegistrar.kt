package com.simplemq.simplemq.service

import com.simplemq.simplemq.repository.MessageRepository
import com.simplemq.simplemq.repository.QueueRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class MetricsRegistrar(
    private val queueRepository: QueueRepository,
    private val messageRepository: MessageRepository,
    private val meterRegistry: MeterRegistry,
) : ApplicationListener<ApplicationReadyEvent> {
    private val registeredGauges = ConcurrentHashMap<UUID, MutableList<Gauge>>()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        val queues = queueRepository.findAll()
        queues.forEach { queue ->
            registerGaugesForQueue(queue.queueId)
        }
    }

    fun registerGaugesForQueue(queueId: UUID) {
        val gauges = mutableListOf<Gauge>()

        val queueDepthGauge =
            Gauge
                .builder("simplemq.queue.depth", { 0.0 }) { _ ->
                    queueRepository.findById(queueId)
                        .map { it.currentMessageCount.toDouble() }
                        .orElse(0.0)
                }
                .description("Current number of messages in the queue")
                .tag("queue_id", queueId.toString())
                .register(meterRegistry)
        gauges.add(queueDepthGauge)

        val inFlightCountGauge =
            Gauge
                .builder("simplemq.queue.inflight_count", { 0.0 }) { _ ->
                    messageRepository.countInFlightMessages(queueId, LocalDateTime.now()).toDouble()
                }
                .description("Number of messages currently in flight")
                .tag("queue_id", queueId.toString())
                .register(meterRegistry)
        gauges.add(inFlightCountGauge)

        val oldestWaitingMessageAgeGauge =
            Gauge
                .builder("simplemq.queue.oldest_waiting_message_age_seconds", { 0.0 }) { _ ->
                    val queue = queueRepository.findById(queueId).orElse(null)
                    if (queue != null) {
                        messageRepository.findOldestWaitingMessageAge(queueId, LocalDateTime.now(), queue.maxDeliveries)
                    } else {
                        0.0
                    }
                }
                .description("Age of the oldest waiting message in seconds")
                .tag("queue_id", queueId.toString())
                .register(meterRegistry)
        gauges.add(oldestWaitingMessageAgeGauge)

        registeredGauges[queueId] = gauges
    }
}
