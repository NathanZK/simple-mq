package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime
import java.util.UUID

data class MessagePageResponse(
    val messages: List<MessageResponse>,
    @JsonProperty("next_cursor_created_at")
    val nextCursorCreatedAt: LocalDateTime?,
    @JsonProperty("next_cursor_message_id")
    val nextCursorMessageId: UUID?,
)
