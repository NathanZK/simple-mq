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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID

class QueueIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

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

        // Verify DLQ contains the exhausted message using peek endpoint
        val dlqPeekResponse =
            restTemplate.getForEntity(
                "/api/queues/$dlqId/messages/peek?limit=10",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, dlqPeekResponse.statusCode)
        val dlqPeekBody = dlqPeekResponse.body!!
        val dlqMessages = dlqPeekBody["messages"] as List<Map<*, *>>
        assertEquals(1, dlqMessages.size, "DLQ should contain exactly 1 message")

        // Verify the actual message content (note: message_id changes when moved to DLQ)
        val dlqMessage = dlqMessages[0]
        assertEquals("dlq test message", dlqMessage["data"])
        assertEquals(2, dlqMessage["delivery_count"]) // Should show max deliveries reached
        assertNotNull(dlqMessage["message_id"]) // Should have a new message ID
        assertNull(dlqPeekBody["next_cursor_created_at"]) // Should be null since there's only one message
        assertNull(dlqPeekBody["next_cursor_message_id"]) // Should be null since there's only one message

        // Verify the original queue is now empty
        val finalDequeueResponse =
            restTemplate.getForEntity(
                "/api/queues/$quickQueueId/messages",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, finalDequeueResponse.statusCode)
        assertNull(finalDequeueResponse.body!!["message"])
    }

    @Test
    fun `should peek messages with pagination`() {
        // Enqueue multiple messages
        for (i in 1..5) {
            val enqueueRequest = EnqueueMessageRequest(data = "test message $i")
            val enqueueResponse =
                restTemplate.postForEntity(
                    "/api/queues/$testQueueId/messages",
                    enqueueRequest,
                    Map::class.java,
                )
            assertEquals(HttpStatus.CREATED, enqueueResponse.statusCode)
        }

        // Peek first page (limit 2)
        val firstPageResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=2",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, firstPageResponse.statusCode)
        val firstPage = firstPageResponse.body!!
        val firstPageMessages = firstPage["messages"] as List<Map<*, *>>
        assertEquals(2, firstPageMessages.size)
        assertEquals("test message 1", firstPageMessages[0]["data"])
        assertEquals("test message 2", firstPageMessages[1]["data"])
        assertNotNull(firstPage["next_cursor_created_at"])
        assertNotNull(firstPage["next_cursor_message_id"])

        // Peek second page using cursor
        val cursorCreatedAt = firstPage["next_cursor_created_at"].toString()
        val cursorMessageId = firstPage["next_cursor_message_id"].toString()
        val secondPageResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=2&cursorCreatedAt=$cursorCreatedAt&cursorMessageId=$cursorMessageId",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, secondPageResponse.statusCode)
        val secondPage = secondPageResponse.body!!
        val secondPageMessages = secondPage["messages"] as List<Map<*, *>>
        assertEquals(2, secondPageMessages.size)
        assertEquals("test message 3", secondPageMessages[0]["data"])
        assertEquals("test message 4", secondPageMessages[1]["data"])
        assertNotNull(secondPage["next_cursor_created_at"])
        assertNotNull(secondPage["next_cursor_message_id"])

        // Peek final page
        val secondCursorCreatedAt = secondPage["next_cursor_created_at"].toString()
        val secondCursorMessageId = secondPage["next_cursor_message_id"].toString()
        val finalPageResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=2&cursorCreatedAt=$secondCursorCreatedAt" +
                    "&cursorMessageId=$secondCursorMessageId",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, finalPageResponse.statusCode)
        val finalPage = finalPageResponse.body!!
        val finalPageMessages = finalPage["messages"] as List<Map<*, *>>
        assertEquals(1, finalPageMessages.size)
        assertEquals("test message 5", finalPageMessages[0]["data"])
        assertNull(finalPage["next_cursor_created_at"]) // EOF
        assertNull(finalPage["next_cursor_message_id"]) // EOF
    }

    @Test
    fun `should peek messages from empty queue`() {
        val stringResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=5",
                String::class.java,
            )

        assertEquals(HttpStatus.OK, stringResponse.statusCode)

        // Parse the JSON response
        val objectMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val body = objectMapper.readValue(stringResponse.body!!, Map::class.java)

        val messages = body["messages"] as List<*>
        assertEquals(0, messages.size)
        assertNull(body["next_cursor_created_at"])
        assertNull(body["next_cursor_message_id"])
    }

    @Test
    fun `should peek messages including in-flight messages`() {
        // Enqueue a message
        val enqueueRequest = EnqueueMessageRequest(data = "test message for peek")
        val enqueueResponse =
            restTemplate.postForEntity(
                "/api/queues/$testQueueId/messages",
                enqueueRequest,
                Map::class.java,
            )
        assertEquals(HttpStatus.CREATED, enqueueResponse.statusCode)
        val messageId = enqueueResponse.body!!["message_id"].toString()

        // Dequeue the message (makes it in-flight)
        val dequeueResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, dequeueResponse.statusCode)
        val dequeuedMessage = dequeueResponse.body!!["message"] as Map<*, *>
        assertEquals(messageId, dequeuedMessage["message_id"].toString())

        // Peek should still show the in-flight message
        val peekResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=10",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, peekResponse.statusCode)
        val peekBody = peekResponse.body!!
        val peekMessages = peekBody["messages"] as List<Map<*, *>>
        assertEquals(1, peekMessages.size)
        assertEquals("test message for peek", peekMessages[0]["data"])
        assertEquals(messageId, peekMessages[0]["message_id"].toString())
        assertEquals(1, peekMessages[0]["delivery_count"]) // Should show incremented delivery count
    }

    @Test
    fun `should validate limit parameter in peek endpoint`() {
        // Test invalid limit (0)
        val invalidLimitResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=0",
                Map::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, invalidLimitResponse.statusCode)

        // Test invalid limit (negative)
        val negativeLimitResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=-5",
                Map::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, negativeLimitResponse.statusCode)

        // Test invalid limit (exceeds max)
        val largeLimitResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=101",
                Map::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, largeLimitResponse.statusCode)

        // Test valid limit (100)
        val validLimitResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=100",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, validLimitResponse.statusCode)
    }

    @Test
    fun `should use messageId as tiebreaker when messages share same createdAt`() {
        // Insert 2 messages with identical createdAt to test tie-breaker logic
        val sameTimestamp = "2026-04-01T10:00:00"
        val msgA = "11111111-1111-1111-1111-111111111111"
        val msgB = "22222222-2222-2222-2222-222222222222"

        jdbcTemplate.execute(
            """
            INSERT INTO messages (message_id, queue_id, data, delivery_count, visible_at, created_at)
            VALUES ('$msgA'::uuid, '$testQueueId'::uuid, 'tiebreaker msg A', 0, '$sameTimestamp', '$sameTimestamp')
            """,
        )

        jdbcTemplate.execute(
            """
            INSERT INTO messages (message_id, queue_id, data, delivery_count, visible_at, created_at)
            VALUES ('$msgB'::uuid, '$testQueueId'::uuid, 'tiebreaker msg B', 0, '$sameTimestamp', '$sameTimestamp')
            """,
        )

        // Update queue count
        jdbcTemplate.execute(
            "UPDATE queues SET current_message_count = 2 WHERE queue_id = '$testQueueId'::uuid",
        )

        // Peek first page (limit 1) - should return msg A
        val firstPageResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=1",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, firstPageResponse.statusCode)
        val firstPage = firstPageResponse.body!!
        val firstPageMessages = firstPage["messages"] as List<Map<*, *>>
        assertEquals(1, firstPageMessages.size)
        assertEquals("tiebreaker msg A", firstPageMessages[0]["data"])
        // Cursor should point to msg A (the last message on this page)
        assertEquals(sameTimestamp, firstPage["next_cursor_created_at"].toString())
        assertEquals(msgA, firstPage["next_cursor_message_id"].toString())

        // Peek second page using cursor - should return msg B (tie-breaker works if this succeeds)
        val cursorCreatedAt = firstPage["next_cursor_created_at"].toString()
        val cursorMessageId = firstPage["next_cursor_message_id"].toString()
        val secondPageResponse =
            restTemplate.getForEntity(
                "/api/queues/$testQueueId/messages/peek?limit=1&cursorCreatedAt=$cursorCreatedAt&cursorMessageId=$cursorMessageId",
                Map::class.java,
            )
        assertEquals(HttpStatus.OK, secondPageResponse.statusCode)
        val secondPage = secondPageResponse.body!!
        val secondPageMessages = secondPage["messages"] as List<Map<*, *>>
        assertEquals(1, secondPageMessages.size)
        // If tie-breaker works, we get msg B not msg A again
        assertEquals("tiebreaker msg B", secondPageMessages[0]["data"])
        assertNull(secondPage["next_cursor_created_at"]) // EOF
        assertNull(secondPage["next_cursor_message_id"]) // EOF
    }
}
