package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class GetQueueMetadataResponse(
    @JsonProperty("queue_id")
    val queueId: UUID,
    @JsonProperty("queue_name")
    val queueName: String,
    @JsonProperty("queue_size")
    val queueSize: Int,
    @JsonProperty("visibility_timeout")
    val visibilityTimeout: Int,
    @JsonProperty("max_deliveries")
    val maxDeliveries: Int,
    @JsonProperty("current_message_count")
    val currentMessageCount: Int,
    @JsonProperty("dlq_id")
    val dlqId: UUID?,
)
