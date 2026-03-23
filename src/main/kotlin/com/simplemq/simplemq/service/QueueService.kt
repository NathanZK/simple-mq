package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.QueueRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QueueService(
    private val queueRepository: QueueRepository,
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
}
