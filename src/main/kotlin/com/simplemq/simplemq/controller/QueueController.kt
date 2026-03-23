package com.simplemq.simplemq.controller

import com.simplemq.simplemq.dto.CreateQueueRequest
import com.simplemq.simplemq.dto.CreateQueueResponse
import com.simplemq.simplemq.service.QueueService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
}
