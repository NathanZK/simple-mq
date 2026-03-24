package com.simplemq.simplemq.dto

import java.util.UUID

data class GetQueueMetadataResponse(
    val queue_id: UUID,
    val queue_name: String,
    val queue_size: Int,
    val visibility_timeout: Int,
    val max_deliveries: Int,
    val current_message_count: Int,
    val dlq_id: UUID?,
)
