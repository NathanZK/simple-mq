package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.entity.Message
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.MessageRepository
import com.simplemq.simplemq.repository.QueueRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class QueueServiceTest {
    @Mock
    private lateinit var queueRepository: QueueRepository

    @Mock
    private lateinit var messageRepository: MessageRepository

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

    @Test
    fun `getQueueMetadata should return correct response when queue exists`() {
        // Given
        val queueId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "orders-queue",
                queueSize = 5000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 42,
                parentQueueId = null,
            )

        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))

        // When
        val response = queueService.getQueueMetadata(queueId.toString())

        // Then
        verify(queueRepository, times(1)).findById(queueId)
        assertEquals(queueId, response.queue_id)
        assertEquals("orders-queue", response.queue_name)
        assertEquals(5000, response.queue_size)
        assertEquals(30, response.visibility_timeout)
        assertEquals(5, response.max_deliveries)
        assertEquals(42, response.current_message_count)
        assertNull(response.dlq_id)
    }

    @Test
    fun `getQueueMetadata should return correct response when queue has DLQ`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "orders-queue",
                queueSize = 5000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 42,
                parentQueueId = dlqId,
            )

        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))

        // When
        val response = queueService.getQueueMetadata(queueId.toString())

        // Then
        verify(queueRepository, times(1)).findById(queueId)
        assertEquals(dlqId, response.dlq_id)
    }

    @Test
    fun `getQueueMetadata should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.empty())

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.getQueueMetadata(queueId.toString())
            }

        assertEquals("Queue not found with ID: $queueId", exception.message)
        verify(queueRepository, times(1)).findById(queueId)
    }

    @Test
    fun `getQueueMetadata should throw IllegalArgumentException for invalid UUID format`() {
        // Given
        val invalidQueueId = "invalid-uuid-format"

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.getQueueMetadata(invalidQueueId)
            }

        assertTrue(exception.message!!.contains("Invalid UUID"))
        verify(queueRepository, never()).findById(any())
    }

    @Test
    fun `enqueueMessage should save message with generated ID and return response`() {
        // Given
        val queueId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
            )
        val request = EnqueueMessageRequest(data = "test message data")

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.save(any<Message>())).thenAnswer { invocation ->
            invocation.getArgument<Message>(0)
        }

        val response = queueService.enqueueMessage(queueId.toString(), request)

        // Then
        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, times(1)).save(any<Message>())
        assertNotNull(response.message_id)
    }

    @Test
    fun `enqueueMessage should throw IllegalStateException when queue is full`() {
        // Given
        val queueId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 1000,
            )

        val request = EnqueueMessageRequest(data = "test message")

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))

        // Then
        val exception =
            assertThrows(IllegalStateException::class.java) {
                queueService.enqueueMessage(queueId.toString(), request)
            }

        assertEquals("Queue is full (capacity: 1000, current: 1000)", exception.message)
        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, never()).save(any())
    }

    @Test
    fun `enqueueMessage should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val request = EnqueueMessageRequest(data = "test message")

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.empty())

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.enqueueMessage(queueId.toString(), request)
            }

        assertEquals("Queue not found with ID: $queueId", exception.message)
        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, never()).save(any())
    }
}
