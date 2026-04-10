package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.dto.DequeueMessageResponse
import com.simplemq.simplemq.dto.DequeuedMessage
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.dto.EnqueueMessageResponse
import com.simplemq.simplemq.dto.GetQueueMetadataResponse
import com.simplemq.simplemq.entity.Message
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.MessageRepository
import com.simplemq.simplemq.repository.QueueRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class QueueService(
    private val queueRepository: QueueRepository,
    private val messageRepository: MessageRepository,
    private val metricsRegistrar: MetricsRegistrar,
    private val meterRegistry: MeterRegistry,
) {
    private val counterCache = ConcurrentHashMap<String, Counter>()

    companion object {
        private const val ENQUEUE_TOTAL_METRIC = "simplemq.enqueue.total"
        private const val DEQUEUE_TOTAL_METRIC = "simplemq.dequeue.total"
        private const val REQUEUE_TOTAL_METRIC = "simplemq.requeue.total"
        private const val MESSAGE_NOT_FOUND_ERROR = "Message not found"
    }

    private fun incrementCounter(
        name: String,
        queueId: String,
        outcome: String,
    ) {
        val key = "$name:$queueId:$outcome"
        val counter =
            counterCache.computeIfAbsent(
                key,
            ) {
                Counter.builder(name)
                    .tag("queue_id", queueId)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
            }
        counter.increment()
    }

    fun createQueue(request: CreateQueueRequest): CreateQueueResponse {
        val queue =
            Queue(
                queueId = UUID.randomUUID(),
                queueName = request.queueName,
                queueSize = request.queueSize,
                visibilityTimeout = request.visibilityTimeout,
                maxDeliveries = request.maxDeliveries,
                currentMessageCount = 0,
            )

        val savedQueue = queueRepository.save(queue)

        metricsRegistrar.registerGaugesForQueue(savedQueue.queueId)

        return CreateQueueResponse(savedQueue.queueId)
    }

    fun getQueueMetadata(queueId: String): GetQueueMetadataResponse {
        val queueIdAsUUID = UUID.fromString(queueId)
        val queue =
            queueRepository.findById(queueIdAsUUID)
                .orElseThrow {
                    IllegalArgumentException("Queue not found with ID: $queueId")
                }

        return GetQueueMetadataResponse(
            queueId = queue.queueId,
            queueName = queue.queueName,
            queueSize = queue.queueSize,
            visibilityTimeout = queue.visibilityTimeout,
            maxDeliveries = queue.maxDeliveries,
            currentMessageCount = queue.currentMessageCount,
            dlqId = queue.dlqId,
        )
    }

    @Transactional
    fun enqueueMessage(
        queueId: String,
        request: EnqueueMessageRequest,
    ): EnqueueMessageResponse {
        val queueIdAsUUID = UUID.fromString(queueId)
        val queue =
            queueRepository.findById(queueIdAsUUID)
                .orElseThrow {
                    incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "queue_not_found")
                    IllegalArgumentException("Queue not found with ID: $queueId")
                }

        // Check queue capacity
        if (queue.currentMessageCount >= queue.queueSize) {
            incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "queue_full")
            throw IllegalStateException("Queue is full (capacity: ${queue.queueSize}, current: ${queue.currentMessageCount})")
        }

        // Create message
        val message =
            Message(
                queueId = queueIdAsUUID,
                data = request.data,
                visibleAt = LocalDateTime.now(),
            )

        val savedMessage = messageRepository.save(message)

        // Update queue message count
        val updatedQueue = queue.copy(currentMessageCount = queue.currentMessageCount + 1)
        queueRepository.save(updatedQueue)

        incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "success")

        return EnqueueMessageResponse(savedMessage.messageId)
    }

    @Transactional
    fun dequeueMessage(queueId: String): DequeueMessageResponse {
        val queueIdAsUUID = UUID.fromString(queueId)
        val now = LocalDateTime.now()

        // Get the queue first
        val queue =
            queueRepository
                .findById(queueIdAsUUID)
                .orElseThrow {
                    incrementCounter(DEQUEUE_TOTAL_METRIC, queueId, "queue_not_found")
                    IllegalArgumentException("Queue not found with ID: $queueId")
                }

        // Step 1: Find exhausted messages first
        val exhaustedMessages =
            messageRepository.findExhaustedMessages(
                queueId = queueIdAsUUID,
                maxDeliveries = queue.maxDeliveries,
                now = now,
            )

        // Step 2: Only create/get DLQ if there are exhausted messages
        if (exhaustedMessages.isNotEmpty()) {
            val finalDlq =
                if (queue.dlqId == null) {
                    // Create new DLQ
                    val newDlq =
                        Queue(
                            queueId = UUID.randomUUID(),
                            queueName = queue.queueName + "-dlq",
                            queueSize = queue.queueSize,
                            visibilityTimeout = queue.visibilityTimeout,
                            maxDeliveries = queue.maxDeliveries,
                            currentMessageCount = 0,
                            dlqId = null,
                        )
                    val savedDlq = queueRepository.save(newDlq)

                    metricsRegistrar.registerGaugesForQueue(savedDlq.queueId)

                    // Update the parent queue to point to the new DLQ
                    val updatedQueue = queue.copy(dlqId = savedDlq.queueId)
                    queueRepository.save(updatedQueue)

                    savedDlq
                } else {
                    // Use existing DLQ
                    val dlqId = queue.dlqId!!
                    queueRepository.findById(dlqId).orElseThrow()
                }

            // Calculate how many messages can be moved to DLQ
            val availableSpace = finalDlq.queueSize - finalDlq.currentMessageCount
            val messagesToMove =
                if (availableSpace > 0) {
                    exhaustedMessages.take(availableSpace)
                } else {
                    emptyList()
                }

            if (messagesToMove.isNotEmpty()) {
                // Move available messages to DLQ in batch
                val dlqMessages =
                    messagesToMove.map { exhaustedMessage ->
                        Message(
                            messageId = UUID.randomUUID(),
                            queueId = finalDlq.queueId,
                            data = exhaustedMessage.data,
                            deliveryCount = exhaustedMessage.deliveryCount,
                            visibleAt = LocalDateTime.now(),
                            createdAt = exhaustedMessage.createdAt,
                        )
                    }

                // Save all DLQ messages at once
                messageRepository.saveAll(dlqMessages)

                // Delete only the messages that were moved to DLQ
                messageRepository.deleteAll(messagesToMove)

                // Update queue counts
                queue.currentMessageCount -= messagesToMove.size
                finalDlq.currentMessageCount += messagesToMove.size

                queueRepository.save(queue)
                queueRepository.save(finalDlq)
            }
            // Note: If DLQ is full or not enough space, remaining exhausted messages stay in source queue
            // This should be monitored via metrics in a real implementation
        }

        // Step 3: Dequeue next available message
        val message =
            messageRepository.findAndLockNextAvailableMessage(
                queueId = queueIdAsUUID,
                maxDeliveries = queue.maxDeliveries,
                now = now,
            )

        return if (message != null) {
            // Update the message
            val newVisibleAt = now.plusSeconds(queue.visibilityTimeout.toLong())
            messageRepository.updateMessageDelivery(
                messageId = message.messageId,
                deliveryCount = message.deliveryCount + 1,
                visibleAt = newVisibleAt,
            )

            incrementCounter(DEQUEUE_TOTAL_METRIC, queueId, "success")

            DequeueMessageResponse(
                message =
                    DequeuedMessage(
                        messageId = message.messageId,
                        data = message.data,
                        invisibleUntil = newVisibleAt,
                    ),
            )
        } else {
            incrementCounter(DEQUEUE_TOTAL_METRIC, queueId, "empty")

            DequeueMessageResponse(message = null)
        }
    }

    @Transactional
    fun deleteMessage(
        queueId: String,
        messageId: String,
    ) {
        val queueIdAsUUID = UUID.fromString(queueId)
        val messageIdAsUUID = UUID.fromString(messageId)

        // Check if queue exists
        val queue =
            queueRepository.findById(queueIdAsUUID)
                .orElseThrow { IllegalArgumentException("Queue not found with ID: $queueId") }

        // Check if message exists and belongs to the specified queue
        messageRepository.findByMessageIdAndQueueId(messageIdAsUUID, queueIdAsUUID)
            ?: run {
                incrementCounter("simplemq.ack.total", queueId, "message_not_found")
                throw IllegalArgumentException(MESSAGE_NOT_FOUND_ERROR)
            }

        // Delete the message
        messageRepository.deleteById(messageIdAsUUID)

        // Update queue message count
        val updatedQueue = queue.copy(currentMessageCount = queue.currentMessageCount - 1)
        queueRepository.save(updatedQueue)

        incrementCounter("simplemq.ack.total", queueId, "success")
    }

    @Transactional
    fun requeueMessage(
        messageId: String,
        queueId: String,
    ): EnqueueMessageResponse {
        val messageIdAsUUID = UUID.fromString(messageId)
        val queueIdAsUUID = UUID.fromString(queueId)

        // Look up message by messageId
        val message =
            messageRepository.findByMessageId(messageIdAsUUID)
                ?: run {
                    incrementCounter(REQUEUE_TOTAL_METRIC, queueId, "message_not_found")
                    throw IllegalArgumentException(MESSAGE_NOT_FOUND_ERROR)
                }

        // Look up source queue
        val sourceQueue =
            queueRepository.findById(message.queueId)
                .orElseThrow { IllegalArgumentException("Source queue not found") }

        // Look up destination queue
        val destinationQueue =
            queueRepository.findById(queueIdAsUUID)
                .orElseThrow {
                    incrementCounter(REQUEUE_TOTAL_METRIC, queueId, "queue_not_found")
                    IllegalArgumentException("Queue not found with ID: $queueId")
                }

        // Check destination queue capacity
        if (destinationQueue.currentMessageCount >= destinationQueue.queueSize) {
            incrementCounter(REQUEUE_TOTAL_METRIC, queueId, "queue_full")
            throw IllegalStateException(
                "Destination queue is full (capacity: ${destinationQueue.queueSize}, current: ${destinationQueue.currentMessageCount})",
            )
        }

        // Update the message: set queue_id to queueId, reset visibility timeout and delivery count
        val updatedVisibleAt = LocalDateTime.now() // Available immediately for processing
        messageRepository.updateMessageQueue(messageIdAsUUID, queueIdAsUUID)
        messageRepository.updateMessageDelivery(
            messageId = messageIdAsUUID,
            deliveryCount = 0,
            visibleAt = updatedVisibleAt,
        )

        // Update queue message counts
        val updatedSourceQueue = sourceQueue.copy(currentMessageCount = sourceQueue.currentMessageCount - 1)
        val updatedDestinationQueue = destinationQueue.copy(currentMessageCount = destinationQueue.currentMessageCount + 1)

        queueRepository.save(updatedSourceQueue)
        queueRepository.save(updatedDestinationQueue)

        incrementCounter(REQUEUE_TOTAL_METRIC, queueId, "success")

        return EnqueueMessageResponse(
            messageId = messageIdAsUUID,
        )
    }

    @Transactional
    fun deleteQueue(queueId: String) {
        val queueIdAsUUID = UUID.fromString(queueId)

        // Check if queue exists
        queueRepository.findById(queueIdAsUUID)
            .orElseThrow {
                incrementCounter("simplemq.delete_queue.total", queueId, "queue_not_found")
                IllegalArgumentException("Queue not found with ID: $queueId")
            }

        // Delete all messages in the queue
        messageRepository.deleteAllByQueueId(queueIdAsUUID)

        // Delete the queue
        queueRepository.deleteById(queueIdAsUUID)

        // Deregister gauges for the deleted queue
        metricsRegistrar.deregisterGaugesForQueue(queueIdAsUUID)

        incrementCounter("simplemq.delete_queue.total", queueId, "success")
    }
}
