package com.simplemq.simplemq.repository

import com.simplemq.simplemq.entity.Queue
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface QueueRepository : JpaRepository<Queue, UUID> {
    @Modifying
    @Query(
        """
        UPDATE Queue q
        SET q.currentMessageCount = q.currentMessageCount + 1
        WHERE q.queueId = :queueId AND q.currentMessageCount < q.queueSize
        """,
    )
    fun incrementMessageCountIfNotFull(
        @Param("queueId") queueId: UUID,
    ): Int
}
