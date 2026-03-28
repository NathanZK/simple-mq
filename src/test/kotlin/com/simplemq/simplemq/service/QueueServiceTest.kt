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
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        assertNull(savedQueueArgument.dlqId)
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
                dlqId = null,
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
                dlqId = dlqId,
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

    @Test
    fun `dequeueMessage should return message when available and update delivery count`() {
        // Given
        val queueId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 1,
            )
        val message =
            Message(
                messageId = messageId,
                queueId = queueId,
                data = "test message",
                deliveryCount = 0,
                visibleAt = now.minusSeconds(10),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(emptyList())
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(message)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then - verify the response is correct first
        assertNotNull(response.message)
        assertEquals(messageId, response.message!!.message_id)
        assertEquals("test message", response.message!!.data)
        // Check that visible_until is in the future (within reasonable range)
        val expectedVisibleTime = LocalDateTime.now().plusSeconds(30)
        val actualVisibleTime = response.message!!.visible_until
        assertTrue(
            actualVisibleTime.isAfter(expectedVisibleTime.minusSeconds(5)) &&
                actualVisibleTime.isBefore(expectedVisibleTime.plusSeconds(5)),
        )

        // Then verify the service method calls
        verify(messageRepository, times(1)).updateMessageDelivery(
            messageId = eq(messageId),
            deliveryCount = eq(1),
            visibleAt = any(),
        )

        // Verify DLQ operations are NOT called since there are no exhausted messages
        verify(queueRepository, times(1)).findById(queueId) // Only initial queue fetch
        verify(queueRepository, never()).save(any<Queue>())
    }

    @Test
    fun `dequeueMessage should return null when no message available`() {
        // Given
        val queueId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findExhaustedMessages(queueId, 5, now)).thenReturn(emptyList())
        whenever(messageRepository.findAndLockNextAvailableMessage(queueId, 5, now)).thenReturn(null)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then
        assertNull(response.message)
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())

        // Verify DLQ operations are NOT called since there are no exhausted messages
        verify(queueRepository, times(1)).findById(queueId) // Only initial queue fetch
        verify(queueRepository, never()).save(any<Queue>())
    }

    @Test
    fun `dequeueMessage should create DLQ if it doesn't exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
            )
        val dlqId = UUID.randomUUID()
        val createdDlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
                dlqId = null,
            )
        val exhaustedMessages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "exhausted",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(10),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(exhaustedMessages)
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.saveAll(any<List<Message>>())).thenReturn(emptyList())
        whenever(queueRepository.save(any<Queue>())).thenReturn(createdDlq, queue.copy(dlqId = createdDlq.queueId))

        queueService.dequeueMessage(queueId.toString())

        // Once for DLQ creation, once for parent queue update, once for parent count update, once for DLQ count update
        verify(queueRepository, times(4)).save(any<Queue>())

        // Verify that the parent queue was updated with the correct dlqId
        val queueCaptor = ArgumentCaptor.forClass(Queue::class.java)
        verify(queueRepository, times(4)).save(capture(queueCaptor))

        val savedQueues = queueCaptor.allValues

        // At minimum, we should have:
        // 1. A DLQ created with dlqId = null
        // 2. The parent queue updated with dlqId pointing to the DLQ
        val dlqSaves = savedQueues.filter { it.dlqId == null && it.queueName.endsWith("-dlq") }
        val parentQueueUpdates = savedQueues.filter { it.queueId == queueId && it.dlqId != null }

        assertTrue(dlqSaves.isNotEmpty(), "Should have at least one DLQ creation")
        assertTrue(parentQueueUpdates.isNotEmpty(), "Should have at least one parent queue update with dlqId set")

        // Verify the DLQ was created correctly
        val actualCreatedDlq = dlqSaves.first()
        assertNull(actualCreatedDlq.dlqId, "DLQ should have dlqId = null")
        assertTrue(actualCreatedDlq.queueName.endsWith("-dlq"), "DLQ should have -dlq suffix")

        // Verify the parent queue was updated to point to some DLQ
        val updatedParentQueue = parentQueueUpdates.first()
        assertNotNull(updatedParentQueue.dlqId, "Parent queue should have dlqId set")

        verify(messageRepository, times(1)).saveAll(any<List<Message>>())
        verify(messageRepository, times(1)).deleteAll(exhaustedMessages)
    }

    @Test
    fun `dequeueMessage should use existing DLQ if it exists`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
                dlqId = dlqId,
            )
        val existingDlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 0,
                dlqId = null,
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(existingDlq))
        whenever(messageRepository.findExhaustedMessages(queueId, 5, now)).thenReturn(emptyList())
        whenever(messageRepository.findAndLockNextAvailableMessage(queueId, 5, now)).thenReturn(null)

        queueService.dequeueMessage(queueId.toString())

        // Then
        verify(queueRepository, never()).save(any<Queue>())
    }

    @Test
    fun `dequeueMessage should move exhausted messages to DLQ and update counts`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val messageId1 = UUID.randomUUID()
        val messageId2 = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
                dlqId = dlqId,
            )
        val dlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 2,
                dlqId = null,
            )
        val exhaustedMessages =
            listOf(
                Message(
                    messageId = messageId1,
                    queueId = queueId,
                    data = "exhausted message 1",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(10),
                ),
                Message(
                    messageId = messageId2,
                    queueId = queueId,
                    data = "exhausted message 2",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(5),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(dlq))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(exhaustedMessages)
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.saveAll(any<List<Message>>())).thenReturn(emptyList())
        whenever(queueRepository.save(any<Queue>())).thenReturn(queue, dlq)

        queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).saveAll(any<List<Message>>())
        verify(messageRepository, times(1)).deleteAll(exhaustedMessages)
        verify(queueRepository, times(1)).save(queue) // Save updated source queue
        verify(queueRepository, times(1)).save(dlq) // Save updated DLQ
    }

    @Test
    fun `dequeueMessage should move partial messages when DLQ has limited space`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
                dlqId = dlqId,
            )
        val almostFullDlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 5,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
                dlqId = null,
            )
        val exhaustedMessages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg1",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(20),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg2",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(15),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg3",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(10),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(almostFullDlq))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(exhaustedMessages)
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.saveAll(any<List<Message>>())).thenReturn(emptyList())
        whenever(queueRepository.save(any<Queue>())).thenReturn(queue, almostFullDlq)

        queueService.dequeueMessage(queueId.toString())

        // Then - Should only move 2 messages (available space), not all 3
        verify(messageRepository, times(1)).saveAll(any<List<Message>>())
        verify(messageRepository, times(1)).deleteAll(any<List<Message>>()) // Only 2 messages deleted
        verify(queueRepository, times(1)).save(queue) // Source queue count reduced by 2
        verify(queueRepository, times(1)).save(almostFullDlq) // DLQ count increased by 2
    }

    @Test
    fun `dequeueMessage should not move messages when DLQ is completely full`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
                dlqId = dlqId,
            )
        val fullDlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 5,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 5,
                dlqId = null,
            )
        val exhaustedMessages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg1",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(20),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg2",
                    deliveryCount = 5,
                    visibleAt = now.minusSeconds(15),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(fullDlq))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(exhaustedMessages)
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)

        queueService.dequeueMessage(queueId.toString())

        // Then - No messages should be moved since DLQ is full
        verify(messageRepository, never()).saveAll(any<List<Message>>())
        verify(messageRepository, never()).deleteAll(any<List<Message>>())
        verify(queueRepository, never()).save(queue) // Source queue count unchanged
        verify(queueRepository, never()).save(fullDlq) // DLQ count unchanged

        // No exception should be thrown - this is a graceful degradation
    }

    @Test
    fun `dequeueMessage should increment delivery count correctly`() {
        // Given
        val queueId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 45,
                maxDeliveries = 5,
                currentMessageCount = 1,
            )
        val message =
            Message(
                messageId = messageId,
                queueId = queueId,
                data = "test message",
                deliveryCount = 2,
                visibleAt = now.minusSeconds(10),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(emptyList())
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(message)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).updateMessageDelivery(
            messageId = eq(messageId),
            deliveryCount = eq(3),
            visibleAt = any(),
        )

        assertNotNull(response.message)
        assertEquals(messageId, response.message!!.message_id)
        // Check that visible_until is in the future (within reasonable range)
        val expectedVisibleTime = LocalDateTime.now().plusSeconds(45)
        val actualVisibleTime = response.message!!.visible_until
        assertTrue(
            actualVisibleTime.isAfter(expectedVisibleTime.minusSeconds(5)) &&
                actualVisibleTime.isBefore(expectedVisibleTime.plusSeconds(5)),
        )
    }

    @Test
    fun `dequeueMessage should handle multiple exhausted messages correctly`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 3,
                currentMessageCount = 5,
                dlqId = dlqId,
            )
        val dlq =
            Queue(
                queueId = dlqId,
                queueName = "source-queue-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 3,
                currentMessageCount = 0,
                dlqId = null,
            )
        val exhaustedMessages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg1",
                    deliveryCount = 3,
                    visibleAt = now.minusSeconds(20),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg2",
                    deliveryCount = 4,
                    visibleAt = now.minusSeconds(15),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "msg3",
                    deliveryCount = 3,
                    visibleAt = now.minusSeconds(10),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(dlq))
        whenever(messageRepository.findExhaustedMessages(any(), any(), any())).thenReturn(exhaustedMessages)
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.saveAll(any<List<Message>>())).thenReturn(emptyList())
        whenever(queueRepository.save(any<Queue>())).thenReturn(queue, dlq)

        queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).saveAll(any<List<Message>>())
        verify(messageRepository, times(1)).deleteAll(exhaustedMessages)
        verify(queueRepository, times(2)).save(any<Queue>()) // Once for source queue, once for DLQ
    }

    @Test
    fun `deleteMessage should delete message and update queue count when message exists and belongs to queue`() {
        // Given
        val queueId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
            )
        val message =
            Message(
                messageId = messageId,
                queueId = queueId,
                data = "test message",
                deliveryCount = 1,
                visibleAt = LocalDateTime.now(),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findByMessageIdAndQueueId(messageId, queueId)).thenReturn(message)

        queueService.deleteMessage(queueId.toString(), messageId.toString())

        // Then
        verify(messageRepository, times(1)).findByMessageIdAndQueueId(messageId, queueId)
        verify(messageRepository, times(1)).deleteById(messageId)
        verify(queueRepository, times(1)).save(queue.copy(currentMessageCount = 9))
    }

    @Test
    fun `deleteMessage should throw IllegalArgumentException when message does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val messageId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.findByMessageIdAndQueueId(messageId, queueId)).thenReturn(null)

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.deleteMessage(queueId.toString(), messageId.toString())
            }

        assertEquals("Message not found", exception.message)
        verify(messageRepository, times(1)).findByMessageIdAndQueueId(messageId, queueId)
        verify(messageRepository, never()).deleteById(any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `deleteMessage should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val messageId = UUID.randomUUID()

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.empty())

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.deleteMessage(queueId.toString(), messageId.toString())
            }

        assertEquals("Message not found", exception.message)
        verify(messageRepository, never()).findByMessageIdAndQueueId(any(), any())
        verify(messageRepository, never()).deleteById(any())
    }

    @Test
    fun `requeueMessage should successfully requeue message from any queue to destination queue`() {
        // Given
        val messageId = UUID.randomUUID()
        val sourceQueueId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID()
        val message =
            Message(
                messageId = messageId,
                queueId = sourceQueueId,
                data = "test message data",
                deliveryCount = 3,
                visibleAt = LocalDateTime.now().minusSeconds(10),
            )
        val sourceQueue =
            Queue(
                queueId = sourceQueueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 5,
                dlqId = null,
            )
        val destinationQueue =
            Queue(
                queueId = destinationQueueId,
                queueName = "destination-queue",
                queueSize = 1000,
                visibilityTimeout = 45,
                maxDeliveries = 5,
                currentMessageCount = 2,
            )

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(message)
        whenever(queueRepository.findById(sourceQueueId)).thenReturn(Optional.of(sourceQueue))
        whenever(queueRepository.findById(destinationQueueId)).thenReturn(Optional.of(destinationQueue))
        whenever(queueRepository.save(any<Queue>())).thenReturn(sourceQueue, destinationQueue)

        val response = queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())

        // Then
        verify(messageRepository, times(1)).findByMessageId(messageId)
        verify(messageRepository, times(1)).updateMessageQueue(messageId, destinationQueueId)
        verify(messageRepository, times(1)).updateMessageDelivery(
            messageId = eq(messageId),
            deliveryCount = eq(0),
            visibleAt = any(),
        )
        verify(queueRepository, times(2)).save(any<Queue>())

        assertEquals(messageId, response.message_id)
    }

    @Test
    fun `requeueMessage should throw IllegalArgumentException when message does not exist`() {
        // Given
        val messageId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID()

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(null)

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())
            }

        assertEquals("Message not found", exception.message)
        verify(messageRepository, times(1)).findByMessageId(messageId)
        verify(messageRepository, never()).updateMessageQueue(any(), any())
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `requeueMessage should throw IllegalArgumentException when destination queue does not exist`() {
        // Given
        val messageId = UUID.randomUUID()
        val sourceQueueId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val message =
            Message(
                messageId = messageId,
                queueId = sourceQueueId,
                data = "test message",
                deliveryCount = 2,
                visibleAt = LocalDateTime.now(),
            )
        val sourceQueue =
            Queue(
                queueId = sourceQueueId,
                queueName = "source-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
                dlqId = dlqId,
            )

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(message)
        whenever(queueRepository.findById(sourceQueueId)).thenReturn(Optional.of(sourceQueue))
        whenever(queueRepository.findById(destinationQueueId)).thenReturn(Optional.empty())

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())
            }

        assertEquals("Queue not found with ID: $destinationQueueId", exception.message)
        verify(messageRepository, times(1)).findByMessageId(messageId)
        verify(messageRepository, never()).updateMessageQueue(any(), any())
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `requeueMessage should throw IllegalStateException when destination queue is full`() {
        // Given
        val messageId = UUID.randomUUID()
        val sourceQueueId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val message =
            Message(
                messageId = messageId,
                queueId = sourceQueueId,
                data = "test message",
                deliveryCount = 2,
                visibleAt = LocalDateTime.now(),
            )
        val sourceQueue =
            Queue(
                queueId = sourceQueueId,
                queueName = "source-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
                dlqId = dlqId,
            )
        val fullDestinationQueue =
            Queue(
                queueId = destinationQueueId,
                queueName = "full-queue",
                queueSize = 100,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 100,
            )

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(message)
        whenever(queueRepository.findById(sourceQueueId)).thenReturn(Optional.of(sourceQueue))
        whenever(queueRepository.findById(destinationQueueId)).thenReturn(Optional.of(fullDestinationQueue))

        // Then
        val exception =
            assertThrows(IllegalStateException::class.java) {
                queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())
            }

        assertEquals("Destination queue is full (capacity: 100, current: 100)", exception.message)
        verify(messageRepository, times(1)).findByMessageId(messageId)
        verify(messageRepository, never()).updateMessageQueue(any(), any())
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `requeueMessage should update queue message counts correctly`() {
        // Given
        val messageId = UUID.randomUUID()
        val sourceQueueId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID()
        val dlqId = UUID.randomUUID()
        val message =
            Message(
                messageId = messageId,
                queueId = sourceQueueId,
                data = "test message",
                deliveryCount = 2,
                visibleAt = LocalDateTime.now(),
            )
        val sourceQueue =
            Queue(
                queueId = sourceQueueId,
                queueName = "source-dlq",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 10,
                dlqId = dlqId,
            )
        val destinationQueue =
            Queue(
                queueId = destinationQueueId,
                queueName = "destination-queue",
                queueSize = 1000,
                visibilityTimeout = 60,
                maxDeliveries = 5,
                currentMessageCount = 5,
            )

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(message)
        whenever(queueRepository.findById(sourceQueueId)).thenReturn(Optional.of(sourceQueue))
        whenever(queueRepository.findById(destinationQueueId)).thenReturn(Optional.of(destinationQueue))
        whenever(queueRepository.save(any<Queue>())).thenReturn(sourceQueue, destinationQueue)

        queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())

        // Then
        val capturedQueues = ArgumentCaptor.forClass(Queue::class.java)

        verify(queueRepository, times(2)).save(capture(capturedQueues))
        val allCaptured = capturedQueues.allValues

        assertEquals(9, allCaptured[0].currentMessageCount) // Source queue decremented by 1
        assertEquals(6, allCaptured[1].currentMessageCount) // Destination queue incremented by 1
    }

    @Test
    fun `requeueMessage should throw IllegalArgumentException for invalid message UUID format`() {
        // Given
        val invalidMessageId = "invalid-uuid-format"
        val destinationQueueId = UUID.randomUUID()

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.requeueMessage(invalidMessageId, destinationQueueId.toString())
            }

        assertTrue(exception.message!!.contains("Invalid UUID"))
        verify(messageRepository, never()).findByMessageId(any())
        verify(messageRepository, never()).updateMessageQueue(any(), any())
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `requeueMessage should throw IllegalArgumentException for invalid destination queue UUID format`() {
        // Given
        val messageId = UUID.randomUUID()
        val invalidQueueId = "invalid-uuid-format"

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.requeueMessage(messageId.toString(), invalidQueueId)
            }

        assertTrue(exception.message!!.contains("Invalid UUID"))
        verify(messageRepository, never()).findByMessageId(any())
        verify(messageRepository, never()).updateMessageQueue(any(), any())
        verify(messageRepository, never()).updateMessageDelivery(any(), any(), any())
        verify(queueRepository, never()).save(any())
    }

    @Test
    fun `requeueMessage should handle requeuing to original queue`() {
        // Given
        val messageId = UUID.randomUUID()
        val sourceQueueId = UUID.randomUUID()
        val destinationQueueId = UUID.randomUUID() // Same as original queue
        val message =
            Message(
                messageId = messageId,
                queueId = sourceQueueId,
                data = "original message",
                deliveryCount = 5,
                visibleAt = LocalDateTime.now(),
            )
        val sourceQueue =
            Queue(
                queueId = sourceQueueId,
                queueName = "source-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
                dlqId = null,
            )
        val originalQueue =
            Queue(
                queueId = destinationQueueId,
                queueName = "original-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 7,
            )

        // When
        whenever(messageRepository.findByMessageId(messageId)).thenReturn(message)
        whenever(queueRepository.findById(sourceQueueId)).thenReturn(Optional.of(sourceQueue))
        whenever(queueRepository.findById(destinationQueueId)).thenReturn(Optional.of(originalQueue))
        whenever(queueRepository.save(any<Queue>())).thenReturn(sourceQueue, originalQueue)

        val response = queueService.requeueMessage(messageId.toString(), destinationQueueId.toString())

        // Then
        assertEquals(messageId, response.message_id)

        verify(messageRepository, times(1)).updateMessageQueue(messageId, destinationQueueId)
        verify(messageRepository, times(1)).updateMessageDelivery(
            messageId = eq(messageId),
            deliveryCount = eq(0),
            visibleAt = any(),
        )
    }
}
