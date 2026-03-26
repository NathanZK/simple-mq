package com.simplemq.simplemq.dto

import java.time.LocalDateTime
import java.util.UUID

data class DequeueMessageResponse(
    val message: DequeuedMessage?,
)

data class DequeuedMessage(
    val message_id: UUID,
    val data: String,
    val visible_until: LocalDateTime,
)
