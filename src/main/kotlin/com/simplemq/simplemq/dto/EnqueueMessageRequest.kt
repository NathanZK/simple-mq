package com.simplemq.simplemq.dto

import jakarta.validation.constraints.NotBlank

data class EnqueueMessageRequest(
    @field:NotBlank(message = "Message data is required")
    val data: String,
)
