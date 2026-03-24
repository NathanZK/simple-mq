package com.simplemq.simplemq.repository

import com.simplemq.simplemq.entity.Message
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {
    fun findByQueueId(queueId: UUID): List<Message>
}
