package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class EnqueueMessageResponse(
    @JsonProperty("message_id")
    val messageId: UUID,
)
