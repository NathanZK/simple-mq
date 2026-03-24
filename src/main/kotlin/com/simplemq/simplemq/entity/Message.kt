package com.simplemq.simplemq.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "messages")
data class Message(
    @Id
    @Column(name = "message_id")
    val messageId: UUID = UUID.randomUUID(),
    @Column(name = "queue_id", nullable = false)
    val queueId: UUID,
    @Column(name = "data", nullable = false, columnDefinition = "TEXT")
    val data: String,
    @Column(name = "delivery_count", nullable = false)
    val deliveryCount: Int = 0,
    @Column(name = "visible_at", nullable = false)
    val visibleAt: LocalDateTime,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
