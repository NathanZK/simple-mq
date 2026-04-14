package com.simplemq.simplemq.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.util.UUID

class QueueIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val objectMapper = jacksonObjectMapper()
    private lateinit var testQueueId: String

    @BeforeEach
    fun setUp() {
        // Create a test queue for each test
        val createRequest =
            CreateQueueRequest(
                queueName = "test-queue-${UUID.randomUUID()}",
                queueSize = 10,
                visibilityTimeout = 5,
                maxDeliveries = 3,
            )

        val response =
            restTemplate.postForEntity(
                "/api/queues",
                createRequest,
                Map::class.java,
            )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        testQueueId = response.body!!["queue_id"].toString()
    }

    @Test
    fun `should create queue, enqueue message, and dequeue it with correct data and invisible_until`() {
        // Enqueue a message
        val enqueueRequest = EnqueueMessageRequest(data = "test message content")
        val enqueueResponse =
            restTemplate.postForEntity(
                "/api/queues/$testQueueId/messages",
                enqueueRequest,
                Map::class.java,
            )

        assertEquals(HttpStatus.CREATED, enqueueResponse.statusCode)
        val messageId = enqueueResponse.body!!["message_id"].toString()

        // Dequeue the message
        val dequeueStartedAt = LocalDateTime.now()
        val dequeueResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages",
                Map::class.java,
            )
        val dequeueFinishedAt = LocalDateTime.now()

        assertEquals(HttpStatus.OK, dequeueResponse.statusCode)
        val message = dequeueResponse.body!!["message"] as Map<*, *>

        // Assert message data matches
        assertEquals(messageId, message["message_id"].toString())
        assertEquals("test message content", message["data"])

        // Assert invisible_until is set (should be approximately now + visibility timeout)
        val invisibleUntilStr = message["invisible_until"].toString()
        val invisibleUntil = LocalDateTime.parse(invisibleUntilStr.substring(0, 19)) // Remove timezone part for parsing

        // Allow for some time variance (within 1 second of the bounded range)
        assertTrue(
            !invisibleUntil.isBefore(dequeueStartedAt.plusSeconds(5).minusSeconds(1)) &&
                !invisibleUntil.isAfter(dequeueFinishedAt.plusSeconds(5).plusSeconds(1)),
        )
    }

    @Test
    fun `should return null message when dequeuing from empty queue`() {
        // Try to dequeue from empty queue
        val response =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages",
                Map::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body!!["message"])
    }

    @Test
    fun `should return 429 when enqueuing beyond queue capacity`() {
        // Fill the queue to capacity (10 messages)
        repeat(10) {
            val enqueueRequest = EnqueueMessageRequest(data = "message $it")
            val response =
                restTemplate.postForEntity(
                    "/api/queues/$testQueueId/messages",
                    enqueueRequest,
                    Map::class.java,
                )
            assertEquals(HttpStatus.CREATED, response.statusCode)
        }

        // Try to enqueue one more message (should fail with 429)
        val overflowRequest = EnqueueMessageRequest(data = "overflow message")
        val response =
            restTemplate.postForEntity(
                "/api/queues/$testQueueId/messages",
                overflowRequest,
                Map::class.java,
            )

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        val errorBody = response.body as Map<*, *>
        assertTrue(errorBody["error"].toString().contains("Queue is full"))
    }

    @Test
    fun `should route exhausted message to DLQ after max deliveries`() {
        // Create a queue with short visibility timeout for faster testing
        val quickQueueRequest =
            CreateQueueRequest(
                queueName = "quick-queue-${UUID.randomUUID()}",
                queueSize = 5,
                visibilityTimeout = 1,
                // 1 second timeout
                maxDeliveries = 2,
                // Max 2 deliveries
            )

        val createResponse =
            restTemplate.postForEntity(
                "/api/queues",
                quickQueueRequest,
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, createResponse.statusCode)
        val quickQueueId = createResponse.body!!["queue_id"].toString()

        // Enqueue a message
        val enqueueRequest = EnqueueMessageRequest(data = "dlq test message")
        val enqueueResponse =
            restTemplate.postForEntity(
                "/api/queues/$quickQueueId/messages",
                enqueueRequest,
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, enqueueResponse.statusCode)
        val messageId = enqueueResponse.body!!["message_id"].toString()

        // Dequeue message twice without acknowledging (exhaust max deliveries)
        repeat(2) {
            val dequeueResponse =
                restTemplate.getForEntity(
                    "/api/queues/$quickQueueId/messages",
                    Map::class.java,
                )
            assertEquals(HttpStatus.OK, dequeueResponse.statusCode)
            val message = dequeueResponse.body!!["message"] as Map<*, *>
            assertEquals(messageId, message["message_id"].toString())
            assertEquals("dlq test message", message["data"])

            // Wait for visibility timeout to expire
            Thread.sleep(1500) // Wait 1.5 seconds (longer than 1 second timeout)
        }

        // Third dequeue should return null (message moved to DLQ)
        val thirdDequeueResponse =
            restTemplate.getForEntity(
                "/api/queues/$quickQueueId/messages",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, thirdDequeueResponse.statusCode)
        assertNull(thirdDequeueResponse.body!!["message"])

        // Check that DLQ was created (this is the key verification)
        val queueMetadataResponse =
            restTemplate.getForEntity(
                "/api/queues/$quickQueueId",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, queueMetadataResponse.statusCode)
        val metadata = queueMetadataResponse.body!!
        assertNotNull(metadata["dlq_id"], "DLQ should be created when messages are exhausted")

        val dlqId = metadata["dlq_id"].toString()
        assertTrue(dlqId.isNotEmpty(), "DLQ ID should not be empty")

        // Verify DLQ has exactly 1 message (the exhausted message)
        val dlqMetadataResponse =
            restTemplate.getForEntity(
                "/api/queues/$dlqId",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, dlqMetadataResponse.statusCode)
        val dlqMetadata = dlqMetadataResponse.body!!
        val dlqMessageCount = dlqMetadata["current_message_count"] as Int
        assertEquals(1, dlqMessageCount, "DLQ should contain exactly 1 message")

        // Verify the original queue is now empty
        val finalDequeueResponse =
            restTemplate.getForEntity(
                "/api/queues/$quickQueueId/messages",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, finalDequeueResponse.statusCode)
        assertNull(finalDequeueResponse.body!!["message"])
    }
}
