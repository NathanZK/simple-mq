package com.simplemq.simplemq.service

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.entity.Message
import com.simplemq.simplemq.entity.Queue
import com.simplemq.simplemq.repository.MessageRepository
import com.simplemq.simplemq.repository.QueueRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.capture
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
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

    @Mock
    private lateinit var metricsRegistrar: MetricsRegistrar

    private val meterRegistry: MeterRegistry = SimpleMeterRegistry()

    private lateinit var queueService: QueueService

    @BeforeEach
    fun setUp() {
        queueService = QueueService(queueRepository, messageRepository, metricsRegistrar, meterRegistry)
    }

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

        assertEquals(savedQueue.queueId, response.queueId)
        assertNotNull(response.queueId)
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
        assertNotEquals(response1.queueId, response2.queueId)
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
        assertEquals(queueId, response.queueId)
        assertEquals("orders-queue", response.queueName)
        assertEquals(5000, response.queueSize)
        assertEquals(30, response.visibilityTimeout)
        assertEquals(5, response.maxDeliveries)
        assertEquals(42, response.currentMessageCount)
        assertNull(response.dlqId)
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
        assertEquals(dlqId, response.dlqId)
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
        whenever(queueRepository.incrementMessageCountIfNotFull(queueId)).thenReturn(1)
        whenever(messageRepository.save(any<Message>())).thenAnswer { invocation ->
            invocation.getArgument<Message>(0)
        }

        val response = queueService.enqueueMessage(queueId.toString(), request)

        // Then
        verify(queueRepository, times(1)).incrementMessageCountIfNotFull(queueId)
        verify(messageRepository, times(1)).save(any<Message>())
        assertNotNull(response.messageId)
    }

    @Test
    fun `enqueueMessage should throw IllegalStateException when queue is full`() {
        // Given
        val queueId = UUID.randomUUID()
        val request = EnqueueMessageRequest(data = "test message")

        // When
        whenever(queueRepository.incrementMessageCountIfNotFull(queueId)).thenReturn(0)
        whenever(queueRepository.existsById(queueId)).thenReturn(true)

        // Then
        val exception =
            assertThrows(IllegalStateException::class.java) {
                queueService.enqueueMessage(queueId.toString(), request)
            }

        assertEquals("Queue is full", exception.message)
        verify(queueRepository, times(1)).incrementMessageCountIfNotFull(queueId)
        verify(messageRepository, never()).save(any())
    }

    @Test
    fun `enqueueMessage should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val request = EnqueueMessageRequest(data = "test message")

        // When
        whenever(queueRepository.incrementMessageCountIfNotFull(queueId)).thenReturn(0)

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.enqueueMessage(queueId.toString(), request)
            }

        assertEquals("Queue not found with ID: $queueId", exception.message)
        verify(queueRepository, times(1)).incrementMessageCountIfNotFull(queueId)
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
        val updatedMessage =
            Message(
                messageId = messageId,
                queueId = queueId,
                data = "test message",
                deliveryCount = 1,
                visibleAt = now.plusSeconds(queue.visibilityTimeout.toLong()),
                createdAt = message.createdAt,
            )
        whenever(messageRepository.findLockAndUpdateMessageAtomic(any(), any())).thenReturn(updatedMessage)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then - verify the response is correct first
        assertNotNull(response.message)
        assertEquals(messageId, response.message!!.messageId)
        assertEquals("test message", response.message!!.data)
        assertEquals(1, response.message!!.deliveryCount)
        // Check that invisible_until is in the future (within reasonable range)
        val expectedVisibleTime = LocalDateTime.now().plusSeconds(queue.visibilityTimeout.toLong())
        val actualVisibleTime = response.message!!.invisibleUntil
        assertTrue(
            actualVisibleTime.isAfter(expectedVisibleTime.minusSeconds(5)) &&
                actualVisibleTime.isBefore(expectedVisibleTime.plusSeconds(5)),
        )

        // Then verify the service method calls
        verify(messageRepository, times(1)).findLockAndUpdateMessageAtomic(
            queueId = eq(queueId),
            now = any(),
        )

        // Verify DLQ operations are NOT called since there are no exhausted messages
        verify(queueRepository, times(1)).existsById(queueId) // Called in incrementCounter for success metric
        verify(queueRepository, never()).save(any<Queue>())
    }

    @Test
    fun `dequeueMessage should return null when no message available`() {
        // Given
        val queueId = UUID.randomUUID()
        val now = LocalDateTime.now()

        // When
        whenever(messageRepository.findLockAndUpdateMessageAtomic(queueId, now)).thenReturn(null)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then
        assertNull(response.message)
        verify(messageRepository, times(1)).countExhaustedMessages(eq(queueId), any())
        verify(messageRepository, times(1)).findLockAndUpdateMessageAtomic(eq(queueId), any())

        // Verify DLQ operations are NOT called since there are no exhausted messages
        verify(queueRepository, times(1)).existsById(queueId) // Called in incrementCounter for empty metric
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
        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(createdDlq))
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.countExhaustedMessages(any(), any())).thenReturn(2)
        whenever(messageRepository.moveMessagesToDlqAtomic(any(), any(), any(), any())).thenReturn(2)
        whenever(queueRepository.getOrCreateDlqAndUpdateParent(any(), any())).thenReturn(createdDlq)

        queueService.dequeueMessage(queueId.toString())

        // Verify atomic DLQ operations - single method does both get/create and parent update
        verify(queueRepository, times(1)).getOrCreateDlqAndUpdateParent(any(), any())
        verify(messageRepository, times(1)).moveMessagesToDlqAtomic(
            sourceQueueId = eq(queueId),
            dlqId = eq(createdDlq.queueId),
            availableSpace = eq(1000),
            // Available space in DLQ (1000-0=1000)
            now = any(),
        )
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
        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(dlq))
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.countExhaustedMessages(any(), any())).thenReturn(2)
        whenever(messageRepository.moveMessagesToDlqAtomic(any(), any(), any(), any())).thenReturn(2)
        whenever(queueRepository.getOrCreateDlqAndUpdateParent(any(), any())).thenReturn(dlq)

        queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).moveMessagesToDlqAtomic(
            sourceQueueId = eq(queueId),
            dlqId = eq(dlq.queueId),
            availableSpace = eq(998),
            // Available space in DLQ (1000-2=998)
            now = any(),
        )
        verify(queueRepository, times(1)).getOrCreateDlqAndUpdateParent(any(), any())
    }

    @Test
    fun `dequeueMessage should move partial messages when DLQ has limited space`() {
        // Given
        val queueId = UUID.randomUUID()
        val dlqId = UUID.nameUUIDFromBytes((queueId.toString() + "-dlq").toByteArray())

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

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(almostFullDlq))
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.countExhaustedMessages(any(), any())).thenReturn(2)
        whenever(messageRepository.moveMessagesToDlqAtomic(any(), any(), any(), any())).thenReturn(2)
        whenever(queueRepository.getOrCreateDlqAndUpdateParent(any(), any())).thenReturn(almostFullDlq)

        queueService.dequeueMessage(queueId.toString())

        verify(messageRepository, times(1)).countExhaustedMessages(eq(queueId), any())
        verify(messageRepository, times(1)).findLockAndUpdateMessageAtomic(eq(queueId), any())
        verify(queueRepository, times(1)).getOrCreateDlqAndUpdateParent(any(), any())
        verify(messageRepository, times(1)).moveMessagesToDlqAtomic(
            sourceQueueId = eq(queueId),
            dlqId = eq(almostFullDlq.queueId),
            availableSpace = eq(2),
            // Available space in DLQ (5-3=2)
            now = any(),
        )
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
        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(fullDlq))
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.countExhaustedMessages(any(), any())).thenReturn(2)
        whenever(queueRepository.getOrCreateDlqAndUpdateParent(any(), any())).thenReturn(fullDlq)
        queueService.dequeueMessage(queueId.toString())

        // Then - No messages should be moved since DLQ is full (availableSpace=0)
        // The atomic operation is not called when availableSpace is 0
        verify(messageRepository, never()).moveMessagesToDlqAtomic(any(), any(), any(), any())
        verify(queueRepository, times(1)).getOrCreateDlqAndUpdateParent(any(), any())

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
        val updatedMessage =
            Message(
                messageId = messageId,
                queueId = queueId,
                data = "test message",
                deliveryCount = 3,
                visibleAt = now.plusSeconds(queue.visibilityTimeout.toLong()),
                createdAt = message.createdAt,
            )
        whenever(messageRepository.findLockAndUpdateMessageAtomic(any(), any())).thenReturn(updatedMessage)

        val response = queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).findLockAndUpdateMessageAtomic(
            queueId = eq(queueId),
            now = any(),
        )

        assertNotNull(response.message)
        assertEquals(messageId, response.message!!.messageId)
        // Check that invisible_until is in the future (within reasonable range)
        val expectedVisibleTime = LocalDateTime.now().plusSeconds(queue.visibilityTimeout.toLong())
        val actualVisibleTime = response.message!!.invisibleUntil
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
        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(queueRepository.findById(dlqId)).thenReturn(Optional.of(dlq))
        whenever(messageRepository.findAndLockNextAvailableMessage(any(), any(), any())).thenReturn(null)
        whenever(messageRepository.countExhaustedMessages(any(), any())).thenReturn(3)
        whenever(messageRepository.moveMessagesToDlqAtomic(any(), any(), any(), any())).thenReturn(3)
        whenever(queueRepository.getOrCreateDlqAndUpdateParent(any(), any())).thenReturn(dlq)

        queueService.dequeueMessage(queueId.toString())

        // Then
        verify(messageRepository, times(1)).moveMessagesToDlqAtomic(
            sourceQueueId = eq(queueId),
            dlqId = eq(dlq.queueId),
            availableSpace = eq(1000),
            // Available space in DLQ (1000-0=1000)
            now = any(),
        )
        verify(queueRepository, times(1)).getOrCreateDlqAndUpdateParent(any(), any())
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

        assertEquals("Queue not found with ID: $queueId", exception.message)
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

        assertEquals(messageId, response.messageId)
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
        assertEquals(messageId, response.messageId)

        verify(messageRepository, times(1)).updateMessageQueue(messageId, destinationQueueId)
        verify(messageRepository, times(1)).updateMessageDelivery(
            messageId = eq(messageId),
            deliveryCount = eq(0),
            visibleAt = any(),
        )
    }

    @Test
    fun `deleteQueue should successfully delete queue and its messages`() {
        // Given
        val queueId = UUID.randomUUID()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
            )

        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))

        // When
        queueService.deleteQueue(queueId.toString())

        // Then
        verify(messageRepository, times(1)).deleteAllByQueueId(queueId)
        verify(queueRepository, times(1)).deleteById(queueId)
    }

    @Test
    fun `deleteQueue should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.empty())

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.deleteQueue(queueId.toString())
            }

        assertEquals("Queue not found with ID: $queueId", exception.message)
        verify(messageRepository, never()).deleteAllByQueueId(any())
        verify(queueRepository, never()).deleteById(any())
    }

    @Test
    fun `deleteQueue should throw IllegalArgumentException for invalid UUID format`() {
        // Given
        val invalidQueueId = "invalid-uuid-format"

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.deleteQueue(invalidQueueId)
            }

        assertTrue(exception.message!!.contains("Invalid UUID string"))
        verify(messageRepository, never()).deleteAllByQueueId(any())
        verify(queueRepository, never()).deleteById(any())
    }

    @Test
    fun `peekMessages should return paginated messages with nextCursor when more messages exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val limit = 2
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 5,
            )
        val messages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 1",
                    deliveryCount = 0,
                    visibleAt = now,
                    createdAt = now.minusMinutes(3),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 2",
                    deliveryCount = 1,
                    visibleAt = now,
                    createdAt = now.minusMinutes(2),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 3",
                    deliveryCount = 0,
                    visibleAt = now,
                    createdAt = now.minusMinutes(1),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))).thenReturn(messages)

        val response = queueService.peekMessages(queueId.toString(), limit, null, null)

        // Then
        assertEquals(limit, response.messages.size)
        assertEquals("message 1", response.messages[0].data)
        assertEquals("message 2", response.messages[1].data)
        assertEquals(messages[1].createdAt, response.nextCursorCreatedAt)
        assertEquals(messages[1].messageId, response.nextCursorMessageId)

        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, times(1)).peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))
    }

    @Test
    fun `peekMessages should return messages with null nextCursor when reaching EOF`() {
        // Given
        val queueId = UUID.randomUUID()
        val limit = 5
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 2,
            )
        val messages =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 1",
                    deliveryCount = 0,
                    visibleAt = now,
                    createdAt = now.minusMinutes(2),
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 2",
                    deliveryCount = 1,
                    visibleAt = now,
                    createdAt = now.minusMinutes(1),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(messageRepository.peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))).thenReturn(messages)

        val response = queueService.peekMessages(queueId.toString(), limit, null, null)

        // Then
        assertEquals(2, response.messages.size)
        assertEquals("message 1", response.messages[0].data)
        assertEquals("message 2", response.messages[1].data)
        assertNull(response.nextCursorCreatedAt)
        assertNull(response.nextCursorMessageId)

        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, times(1)).peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))
    }

    @Test
    fun `peekMessages should work with cursor for pagination`() {
        // Given
        val queueId = UUID.randomUUID()
        val limit = 2
        val afterCreatedAt = LocalDateTime.now().minusMinutes(2)
        val afterMessageId = UUID.randomUUID()
        val expectedNextCursorCreatedAt = LocalDateTime.now()
        val expectedNextCursorMessageId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val queue =
            Queue(
                queueId = queueId,
                queueName = "test-queue",
                queueSize = 1000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
                currentMessageCount = 3,
            )
        val mockedPageResults =
            listOf(
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 1",
                    deliveryCount = 0,
                    visibleAt = now,
                    createdAt = now.minusMinutes(1),
                ),
                Message(
                    messageId = expectedNextCursorMessageId,
                    queueId = queueId,
                    data = "message 2",
                    deliveryCount = 1,
                    visibleAt = now,
                    createdAt = expectedNextCursorCreatedAt,
                ),
                Message(
                    messageId = UUID.randomUUID(),
                    queueId = queueId,
                    data = "message 3",
                    deliveryCount = 0,
                    visibleAt = now,
                    createdAt = now.plusMinutes(1),
                ),
            )

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.of(queue))
        whenever(
            messageRepository.peekMessages(
                eq(queueId),
                eq(afterCreatedAt),
                eq(afterMessageId),
                eq(limit + 1),
            ),
        ).thenReturn(mockedPageResults)

        val response = queueService.peekMessages(queueId.toString(), limit, afterCreatedAt, afterMessageId)

        // Then
        assertEquals(limit, response.messages.size)
        assertEquals("message 1", response.messages[0].data)
        assertEquals("message 2", response.messages[1].data)
        assertEquals(response.nextCursorCreatedAt, expectedNextCursorCreatedAt)
        assertEquals(response.nextCursorMessageId, expectedNextCursorMessageId)

        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, times(1)).peekMessages(eq(queueId), eq(afterCreatedAt), eq(afterMessageId), eq(limit + 1))
    }

    @Test
    fun `peekMessages should return empty list when queue has no messages`() {
        // Given
        val queueId = UUID.randomUUID()
        val limit = 5
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
        whenever(messageRepository.peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))).thenReturn(emptyList())

        val response = queueService.peekMessages(queueId.toString(), limit, null, null)

        // Then
        assertEquals(0, response.messages.size)
        assertNull(response.nextCursorCreatedAt)
        assertNull(response.nextCursorMessageId)

        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, times(1)).peekMessages(eq(queueId), isNull(), isNull(), eq(limit + 1))
    }

    @Test
    fun `peekMessages should throw IllegalArgumentException when queue does not exist`() {
        // Given
        val queueId = UUID.randomUUID()
        val limit = 5

        // When
        whenever(queueRepository.findById(queueId)).thenReturn(Optional.empty())

        // Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.peekMessages(queueId.toString(), limit, null, null)
            }

        assertEquals("Queue not found with ID: $queueId", exception.message)
        verify(queueRepository, times(1)).findById(queueId)
        verify(messageRepository, never()).peekMessages(any(), any(), any(), any())
    }

    @Test
    fun `peekMessages should throw IllegalArgumentException for invalid UUID format`() {
        // Given
        val invalidQueueId = "invalid-uuid-format"
        val limit = 5

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.peekMessages(invalidQueueId, limit, null, null)
            }

        assertTrue(exception.message!!.contains("Invalid UUID string"))
        verify(queueRepository, never()).findById(any())
        verify(messageRepository, never()).peekMessages(any(), any(), any(), any())
    }

    @Test
    fun `peekMessages should throw IllegalArgumentException for invalid limit`() {
        // Given
        val queueId = UUID.randomUUID()

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.peekMessages(queueId.toString(), 0, null, null)
            }

        assertEquals("Limit must be greater than 0", exception.message)
    }

    @Test
    fun `peekMessages should throw IllegalArgumentException for invalid limit (exceeds max)`() {
        // Given
        val queueId = UUID.randomUUID()

        // When & Then
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                queueService.peekMessages(queueId.toString(), 101, null, null)
            }

        assertEquals("Limit must not exceed 100", exception.message)
    }
}
