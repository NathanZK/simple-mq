package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime
import java.util.UUID

data class DequeueMessageResponse(
    val message: DequeuedMessage?,
)

data class DequeuedMessage(
    @JsonProperty("message_id")
    val messageId: UUID,
    val data: String,
    @JsonProperty("invisible_until")
    val invisibleUntil: LocalDateTime,
)
