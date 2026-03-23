package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.QueueRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class QueueServiceTest {
    @Mock
    private lateinit var queueRepository: QueueRepository

    @InjectMocks
    private lateinit var queueService: QueueService

    @Test
    fun `createQueue should save queue with generated UUID and return response`() {
        // Given
        val request =
            CreateQueueRequest(
                queueName = "orders-queue",
                queueSize = 5000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
            )

        val savedQueue =
            Queue(
                queueId = UUID.randomUUID(),
                queueName = request.queueName,
                queueSize = request.queueSize,
                visibilityTimeout = request.visibilityTimeout,
                maxDeliveries = request.maxDeliveries,
                currentMessageCount = 0,
            )

        // When
        whenever(queueRepository.save(any<Queue>())).thenReturn(savedQueue)

        val response = queueService.createQueue(request)

        // Then
        val queueCaptor = ArgumentCaptor.forClass(Queue::class.java)
        verify(queueRepository, times(1)).save(capture(queueCaptor))

        val savedQueueArgument = queueCaptor.value
        assertEquals(request.queueName, savedQueueArgument.queueName)
        assertEquals(request.queueSize, savedQueueArgument.queueSize)
        assertEquals(request.visibilityTimeout, savedQueueArgument.visibilityTimeout)
        assertEquals(request.maxDeliveries, savedQueueArgument.maxDeliveries)
        assertEquals(0, savedQueueArgument.currentMessageCount)
        assertNull(savedQueueArgument.parentQueueId)
        assertNotNull(savedQueueArgument.queueId)

        assertEquals(savedQueue.queueId, response.queue_id)
        assertNotNull(response.queue_id)
    }

    @Test
    fun `createQueue should generate different UUIDs for multiple calls`() {
        // Given
        val request =
            CreateQueueRequest(
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 60,
                maxDeliveries = 3,
            )

        // When
        whenever(queueRepository.save(any<Queue>())).thenAnswer { invocation ->
            invocation.getArgument<Queue>(0)
        }

        val response1 = queueService.createQueue(request)
        val response2 = queueService.createQueue(request)

        // Then
        verify(queueRepository, times(2)).save(any<Queue>())
        assertNotEquals(response1.queue_id, response2.queue_id)
    }
}
