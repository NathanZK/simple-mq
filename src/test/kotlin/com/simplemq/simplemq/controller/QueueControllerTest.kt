package com.simplemq.simplemq.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.service.QueueService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(QueueController::class)
class QueueControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var queueService: QueueService

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `createQueue should return 201 Created with correct response format`() {
        // Given
        val request =
            CreateQueueRequest(
                queueName = "orders-queue",
                queueSize = 5000,
                visibilityTimeout = 30,
                maxDeliveries = 5,
            )

        val expectedQueueId = UUID.randomUUID()
        val expectedResponse = CreateQueueResponse(expectedQueueId)

        whenever(queueService.createQueue(any())).thenReturn(expectedResponse)

        // When & Then
        mockMvc.perform(
            post("/api/queues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.queue_id").value(expectedQueueId.toString()))

        verify(queueService, times(1)).createQueue(request)
    }

    @Test
    fun `createQueue should return 400 Bad Request for invalid data`() {
        // Given
        val invalidRequest =
            CreateQueueRequest(
                // Invalid: empty name, negative size, negative timeout, zero deliveries
                queueName = "",
                queueSize = -1,
                visibilityTimeout = -1,
                maxDeliveries = 0,
            )

        // When & Then
        mockMvc.perform(
            post("/api/queues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)),
        )
            .andExpect(status().isBadRequest())

        verify(queueService, never()).createQueue(any())
    }

    @Test
    fun `createQueue should handle valid request with minimal values`() {
        // Given
        val request =
            CreateQueueRequest(
                queueName = "minimal-queue",
                queueSize = 1,
                visibilityTimeout = 0,
                maxDeliveries = 1,
            )

        val expectedResponse = CreateQueueResponse(UUID.randomUUID())
        whenever(queueService.createQueue(any())).thenReturn(expectedResponse)

        // When & Then
        mockMvc.perform(
            post("/api/queues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.queue_id").exists())

        verify(queueService, times(1)).createQueue(request)
    }

    @Test
    fun `createQueue should return correct JSON structure`() {
        // Given
        val request =
            CreateQueueRequest(
                queueName = "json-test-queue",
                queueSize = 100,
                visibilityTimeout = 15,
                maxDeliveries = 2,
            )

        val expectedResponse = CreateQueueResponse(UUID.randomUUID())
        whenever(queueService.createQueue(any())).thenReturn(expectedResponse)

        // When & Then
        val result =
            mockMvc.perform(
                post("/api/queues")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andReturn().response.contentAsString

        // Verify JSON structure
        assertTrue(result.contains("\"queue_id\""))
        assertFalse(result.contains("\"queueName\""))
        assertFalse(result.contains("\"queueSize\""))
        assertFalse(result.contains("\"visibilityTimeout\""))
        assertFalse(result.contains("\"maxDeliveries\""))
    }
}
