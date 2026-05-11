package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.dto.DequeueMessageResponse
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.dto.EnqueueMessageResponse
import com.simplemq.simplemq.dto.GetQueueMetadataResponse
import com.simplemq.simplemq.dto.MessagePageResponse
import com.simplemq.simplemq.dto.MessageResponse
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
        private const val DLQ_FAILED_METRIC = "simplemq.dlq.failed_total"
        private const val MESSAGE_NOT_FOUND_ERROR = "Message not found"
    }

    /**
     * Register (if needed) and increment a Micrometer counter for a specific queue outcome.
     *
     * If `queueId` cannot be found in the repository and `outcome` is not `"queue_not_found"`,
     * the method returns without creating or incrementing a counter. Counters are cached by
     * the composite key `"$name:$queueId:$outcome"` and are created with `queue_id` and `outcome` tags.
     *
     * @param name The metric name to increment (e.g., `simplemq.enqueue.total`).
     * @param queueId The queue UUID as a string.
     * @param outcome The outcome label for the counter (e.g., `success`, `queue_not_found`, `queue_full`, `empty`, `message_not_found`).
     * @param amount The amount to increment by (defaults to 1.0).
     */
    private fun incrementCounter(
        name: String,
        queueId: String,
        outcome: String,
        amount: Double = 1.0,
    ) {
        // Guard clause: only register counters for existing queues
        val queueIdAsUUID = UUID.fromString(queueId)
        if (!queueRepository.existsById(queueIdAsUUID) && outcome != "queue_not_found") {
            return
        }

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
        counter.increment(amount)
    }

    /**
     * Removes cached counters and MeterRegistry meters associated with the given queue.
     *
     * Scans the in-memory counterCache for keys containing ":$queueId:" and removes each cached
     * Counter from both the cache and the MeterRegistry. Also scans the MeterRegistry and removes
     * any meters whose `queue_id` tag equals the provided queueId.
     *
     * @param queueId The queue identifier (UUID string) whose counters and meters should be deregistered.
     */
    private fun deregisterCountersForQueue(queueId: String) {
        val keysToRemove = counterCache.keys.filter { it.contains(":$queueId:") }
        keysToRemove.forEach { key ->
            counterCache.remove(key)?.let { counter ->
                meterRegistry.remove(counter)
            }
        }

        // Also remove any meters with queue_id tag from the registry directly
        meterRegistry.getMeters()
            .filter { it.getId().getTag("queue_id") == queueId }
            .forEach { meter ->
                meterRegistry.remove(meter)
            }
    }

    /**
     * Creates and persists a new queue with the provided configuration and registers gauges for it.
     *
     * @param request Configuration for the new queue (queueName, queueSize, visibilityTimeout, maxDeliveries).
     * @return The UUID of the newly created queue.
     */
    fun createQueue(request: CreateQueueRequest): CreateQueueResponse {
        val queue =
            Queue(
                queueName = request.queueName,
                queueSize = request.queueSize,
                visibilityTimeout = request.visibilityTimeout,
                maxDeliveries = request.maxDeliveries,
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

        // Atomically increment counter if not full
        val rowsUpdated = queueRepository.incrementMessageCountIfNotFull(queueIdAsUUID)

        if (rowsUpdated == 0) {
            // No rows updated means queue doesn't exist or is full
            // Check which one to return appropriate error
            if (!queueRepository.existsById(queueIdAsUUID)) {
                incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "queue_not_found")
                throw IllegalArgumentException("Queue not found with ID: $queueId")
            } else {
                incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "queue_full")
                throw IllegalStateException("Queue is full")
            }
        }

        // Create message
        val message =
            Message(
                queueId = queueIdAsUUID,
                data = request.data,
                visibleAt = LocalDateTime.now(),
            )

        val savedMessage = messageRepository.save(message)

        incrementCounter(ENQUEUE_TOTAL_METRIC, queueId, "success")

        return EnqueueMessageResponse(savedMessage.messageId)
    }

    @Transactional
    fun dequeueMessage(queueId: String): DequeueMessageResponse {
        val queueIdAsUUID = UUID.fromString(queueId)
        val now = LocalDateTime.now()

        // Move exhausted messages to DLQ
        moveExhaustedMessagesToDLQ(queueIdAsUUID, now)

        // Dequeue a single message
        return dequeueSingleMessage(queueIdAsUUID, now)
    }

    /**
     * Orchestrates the atomic migration of messages that have exceeded their
     * maximum delivery attempts to a Dead Letter Queue (DLQ).
     *
     * DESIGN TRADE-OFF: This implementation favors "Graceful Degradation."
     * To prevent blocking the primary dequeue pipeline, we avoid pessimistic
     * locking on the DLQ. Consequently, the DLQ may temporarily exceed its
     * configured capacity during high-concurrency bursts, ensuring "poison
     * messages" are evicted without stalling primary workers.
     *
     * PRECONDITION: The source queue must exist. Missing queues are handled
     * gracefully with early exit (Zero-Chatter pattern) rather than exceptions.
     *
     * @param queueIdAsUUID The source queue identifier.
     * @param now The reference timestamp for message visibility.
     */
    private fun moveExhaustedMessagesToDLQ(
        queueIdAsUUID: UUID,
        now: LocalDateTime,
    ) {
        // Step 1: Check if there are any exhausted messages to process
        val totalExhausted =
            messageRepository.countExhaustedMessages(
                queueId = queueIdAsUUID,
                now = now,
            )
        if (totalExhausted == 0) return

        // Step 2: Ensure DLQ exists and parent queue references it
        val dlqId = UUID.nameUUIDFromBytes((queueIdAsUUID.toString() + "-dlq").toByteArray())
        val dlq =
            queueRepository.getOrCreateDlqAndUpdateParent(queueIdAsUUID, dlqId)
                ?: throw IllegalArgumentException("Source queue not found: $queueIdAsUUID")

        // Step 3: Register metrics for the DLQ with type="dlq" tag
        metricsRegistrar.registerGaugesForQueue(dlq.queueId, type = "dlq")

        // Step 4: Calculate available space and move messages atomically
        // Note: This accepts a potential race condition where concurrent threads might
        // see stale currentMessageCount values, leading to minor DLQ overflow.
        // This is intentional graceful degradation - exhausted messages that can't
        // fit in DLQ remain in source queue and will be retried in next cycle.
        val availableSpace = (dlq.queueSize - dlq.currentMessageCount).coerceAtLeast(0)

        val movedCount =
            if (availableSpace > 0) {
                messageRepository.moveMessagesToDlqAtomic(
                    sourceQueueId = queueIdAsUUID,
                    dlqId = dlq.queueId,
                    availableSpace = availableSpace,
                    now = now,
                )
            } else {
                0
            }

        // Step 5: Record metrics for messages that couldn't be moved due to DLQ capacity limits
        val failedToMove = totalExhausted - movedCount
        if (failedToMove > 0) {
            incrementCounter(DLQ_FAILED_METRIC, queueIdAsUUID.toString(), "capacity_exceeded", failedToMove.toDouble())
        }
    }

    private fun dequeueSingleMessage(
        queueIdAsUUID: UUID,
        now: LocalDateTime,
    ): DequeueMessageResponse {
        // Single atomic operation: find, lock, and update message delivery
        val updatedMessage =
            messageRepository.findLockAndUpdateMessageAtomic(
                queueId = queueIdAsUUID,
                now = now,
            )

        return if (updatedMessage != null) {
            incrementCounter(DEQUEUE_TOTAL_METRIC, queueIdAsUUID.toString(), "success")

            DequeueMessageResponse(
                message =
                    MessageResponse(
                        messageId = updatedMessage.messageId,
                        data = updatedMessage.data,
                        deliveryCount = updatedMessage.deliveryCount,
                        invisibleUntil = updatedMessage.visibleAt,
                        createdAt = updatedMessage.createdAt,
                    ),
            )
        } else {
            incrementCounter(DEQUEUE_TOTAL_METRIC, queueIdAsUUID.toString(), "empty")
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

    fun peekMessages(
        queueId: String,
        limit: Int,
        cursorCreatedAt: LocalDateTime?,
        cursorMessageId: UUID?,
    ): MessagePageResponse {
        // Validate limit
        require(limit > 0) { "Limit must be greater than 0" }
        require(limit <= 100) { "Limit must not exceed 100" }

        val queueIdAsUUID = UUID.fromString(queueId)

        // Check if queue exists
        queueRepository.findById(queueIdAsUUID)
            .orElseThrow {
                IllegalArgumentException("Queue not found with ID: $queueId")
            }

        // Simplified cursor logic: if no createdAt, message ID is irrelevant
        val actualCursorMessageId = if (cursorCreatedAt != null) cursorMessageId else null

        // Fetch messages with pagination (limit + 1 to detect EOF)
        val messages =
            messageRepository.peekMessages(
                queueId = queueIdAsUUID,
                cursorCreatedAt = cursorCreatedAt,
                cursorMessageId = actualCursorMessageId,
                limit = limit + 1,
            )

        // Determine if there are more messages and get the actual messages to return
        val hasMore = messages.size > limit
        val messagesToReturn = if (hasMore) messages.take(limit) else messages

        // Convert to Message DTOs
        val messageResponses =
            messagesToReturn.map { message ->
                MessageResponse(
                    messageId = message.messageId,
                    data = message.data,
                    deliveryCount = message.deliveryCount,
                    invisibleUntil = message.visibleAt,
                    createdAt = message.createdAt,
                )
            }

        // Determine next cursor - null if no more messages
        val (nextCursorCreatedAt, nextCursorMessageId) =
            if (hasMore) {
                val lastMessage = messagesToReturn.last()
                lastMessage.createdAt to lastMessage.messageId
            } else {
                null to null
            }

        return MessagePageResponse(
            messages = messageResponses,
            nextCursorCreatedAt = nextCursorCreatedAt,
            nextCursorMessageId = nextCursorMessageId,
        )
    }

    /**
     * Deletes a queue and all its messages, then removes associated metrics.
     *
     * Deletes every message belonging to the specified queue, removes the queue record,
     * deregisters gauges and cached counters/meters tied to the queue, and records a
     * delete counter outcome.
     *
     * @param queueId The string representation of the queue UUID to delete.
     * @throws IllegalArgumentException if no queue exists with the given ID (also increments the delete counter with outcome `queue_not_found`).
     */
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

        // Deregister counters for the deleted queue
        deregisterCountersForQueue(queueId)
    }
}
