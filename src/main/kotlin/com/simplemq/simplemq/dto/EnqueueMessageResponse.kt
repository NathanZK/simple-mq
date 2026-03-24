package com.simplemq.simplemq.dto

import java.util.UUID

data class EnqueueMessageResponse(
    val message_id: UUID,
)
