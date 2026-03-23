package com.simplemq.simplemq.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateQueueRequest(
    @field:NotBlank(message = "Queue name is required")
    @field:Size(min = 1, max = 255, message = "Queue name must be between 1 and 255 characters")
    val queueName: String,
    @field:Min(value = 1, message = "Queue size must be at least 1")
    @field:Max(value = 1000000, message = "Queue size cannot exceed 1,000,000")
    val queueSize: Int,
    @field:Min(value = 0, message = "Visibility timeout cannot be negative")
    @field:Max(value = 43200, message = "Visibility timeout cannot exceed 12 hours (43200 seconds)")
    val visibilityTimeout: Int,
    @field:Min(value = 1, message = "Max deliveries must be at least 1")
    @field:Max(value = 100, message = "Max deliveries cannot exceed 100")
    val maxDeliveries: Int,
)
