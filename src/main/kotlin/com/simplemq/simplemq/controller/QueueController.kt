package com.simplemq.simplemq.controller

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.dto.DequeueMessageResponse
import com.simplemq.simplemq.dto.EnqueueMessageRequest
import com.simplemq.simplemq.dto.EnqueueMessageResponse
import com.simplemq.simplemq.dto.GetQueueMetadataResponse
import com.simplemq.simplemq.service.QueueService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/queues")
class QueueController(
    private val queueService: QueueService,
) {
    @PostMapping
    fun createQueue(
        @Valid @RequestBody request: CreateQueueRequest,
    ): ResponseEntity<CreateQueueResponse> {
        val response = queueService.createQueue(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{queue_id}")
    fun getQueueMetadata(
        @PathVariable queue_id: String,
    ): ResponseEntity<GetQueueMetadataResponse> {
        val response = queueService.getQueueMetadata(queue_id)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/{queue_id}/messages")
    fun enqueueMessage(
        @PathVariable queue_id: String,
        @Valid @RequestBody request: EnqueueMessageRequest,
    ): ResponseEntity<EnqueueMessageResponse> {
        val response = queueService.enqueueMessage(queue_id, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{queue_id}/messages")
    fun dequeueMessage(
        @PathVariable queue_id: String,
    ): ResponseEntity<DequeueMessageResponse> {
        val response = queueService.dequeueMessage(queue_id)
        return ResponseEntity.ok(response)
    }

    @DeleteMapping("/{queue_id}/messages/{message_id}")
    fun deleteMessage(
        @PathVariable queue_id: String,
        @PathVariable message_id: String,
    ): ResponseEntity<Void> {
        queueService.deleteMessage(queue_id, message_id)
        return ResponseEntity.ok().build()
    }
}
