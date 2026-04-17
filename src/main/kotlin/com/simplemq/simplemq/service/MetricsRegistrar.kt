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
        // Guard Clause: If we already have gauges for this ID, don't create more!
        if (registeredGauges.containsKey(queueId)) {
            return
        }

        val gauges = mutableListOf<Gauge>()

        val maxDeliveries: Int =
            queueRepository.findById(queueId)
                .map { it.maxDeliveries }
                .orElse(1)

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
                    messageRepository.findOldestWaitingMessageAge(queueId, LocalDateTime.now(), maxDeliveries)
                }
                .description("Age of the oldest waiting message in seconds")
                .tag("queue_id", queueId.toString())
                .register(meterRegistry)
        gauges.add(oldestWaitingMessageAgeGauge)

        registeredGauges[queueId] = gauges
    }

    fun deregisterGaugesForQueue(queueId: UUID) {
        registeredGauges.remove(queueId)?.forEach { gauge ->
            meterRegistry.remove(gauge)
        }
    }
}
