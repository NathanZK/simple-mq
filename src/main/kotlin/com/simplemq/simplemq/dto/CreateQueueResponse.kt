package com.simplemq.simplemq.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class CreateQueueResponse(
    @JsonProperty("queue_id")
    val queueId: UUID,
)
