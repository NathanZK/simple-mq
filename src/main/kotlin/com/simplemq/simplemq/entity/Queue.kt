package com.simplemq.simplemq.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "queues")
data class Queue(
    @Id
    @Column(name = "queue_id")
    val queueId: UUID = UUID.randomUUID(),
    @Column(name = "queue_name", nullable = false)
    val queueName: String,
    @Column(name = "queue_size", nullable = false)
    val queueSize: Int,
    @Column(name = "visibility_timeout", nullable = false)
    val visibilityTimeout: Int,
    @Column(name = "max_deliveries", nullable = false)
    val maxDeliveries: Int,
    @Column(name = "current_message_count", nullable = false)
    val currentMessageCount: Int = 0,
    @Column(name = "parent_queue_id")
    val parentQueueId: UUID? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
