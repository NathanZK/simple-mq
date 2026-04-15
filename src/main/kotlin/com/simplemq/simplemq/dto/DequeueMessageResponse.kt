package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime
import java.util.UUID

data class DequeueMessageResponse(
    val message: MessageResponse?,
)

data class MessageResponse(
    @JsonProperty("message_id")
    val messageId: UUID,
    val data: String,
    @JsonProperty("delivery_count")
    val deliveryCount: Int,
    @JsonProperty("invisible_until")
    val invisibleUntil: LocalDateTime,
    @JsonProperty("created_at")
    val createdAt: LocalDateTime,
)
