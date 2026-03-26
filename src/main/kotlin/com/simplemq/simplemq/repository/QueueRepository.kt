package com.simplemq.simplemq.repository

import com.simplemq.simplemq.entity.Queue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface QueueRepository : JpaRepository<Queue, UUID> {
    fun findByParentQueueId(parentQueueId: UUID): List<Queue>
}
