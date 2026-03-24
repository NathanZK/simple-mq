package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.dto.EnqueueMessageResponse
import com.simplemq.simplemq.dto.GetQueueMetadataResponse
import com.simplemq.simplemq.entity.Message
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.MessageRepository
import com.simplemq.simplemq.repository.QueueRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class QueueService(
    private val queueRepository: QueueRepository,
    private val messageRepository: MessageRepository,
) {
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
            queue_id = queue.queueId,
            queue_name = queue.queueName,
            queue_size = queue.queueSize,
            visibility_timeout = queue.visibilityTimeout,
            max_deliveries = queue.maxDeliveries,
            current_message_count = queue.currentMessageCount,
            dlq_id = queue.parentQueueId,
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
                .orElseThrow { IllegalArgumentException("Queue not found with ID: $queueId") }

        // Check queue capacity
        if (queue.currentMessageCount >= queue.queueSize) {
            throw IllegalStateException("Queue is full (capacity: ${queue.queueSize}, current: ${queue.currentMessageCount})")
        }

        // Create message
        val message =
            Message(
                queueId = queueIdAsUUID,
                data = request.data,
                visibleAt = LocalDateTime.now().plusSeconds(queue.visibilityTimeout.toLong()),
            )

        val savedMessage = messageRepository.save(message)

        // Update queue message count
        val updatedQueue = queue.copy(currentMessageCount = queue.currentMessageCount + 1)
        queueRepository.save(updatedQueue)

        return EnqueueMessageResponse(savedMessage.messageId)
    }
}
