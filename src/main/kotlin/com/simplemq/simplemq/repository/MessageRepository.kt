package com.simplemq.simplemq.repository

import com.simplemq.simplemq.entity.Message
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {
    fun findByQueueId(queueId: UUID): List<Message>

    @Query(
        "SELECT m FROM Message m WHERE m.queueId = :queueId AND m.deliveryCount >= :maxDeliveries AND m.visibleAt <= :now",
    )
    fun findExhaustedMessages(
        @Param("queueId") queueId: UUID,
        @Param("maxDeliveries") maxDeliveries: Int,
        @Param("now") now: LocalDateTime,
    ): List<Message>

    @Query(
        value =
            "SELECT * FROM messages WHERE queue_id = CAST(:queueId AS uuid) " +
                "AND visible_at <= :now AND delivery_count < :maxDeliveries " +
                "ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
        nativeQuery = true,
    )
    fun findAndLockNextAvailableMessage(
        @Param("queueId") queueId: UUID,
        @Param("maxDeliveries") maxDeliveries: Int,
        @Param("now") now: LocalDateTime,
    ): Message?

    @Modifying
    @Query(
        "UPDATE Message m SET m.deliveryCount = :deliveryCount, m.visibleAt = :visibleAt WHERE m.messageId = :messageId",
    )
    fun updateMessageDelivery(
        @Param("messageId") messageId: UUID,
        @Param("deliveryCount") deliveryCount: Int,
        @Param("visibleAt") visibleAt: LocalDateTime,
    )

    @Modifying
    @Query(
        "UPDATE Message m SET m.queueId = :queueId WHERE m.messageId = :messageId",
    )
    fun updateMessageQueue(
        @Param("messageId") messageId: UUID,
        @Param("queueId") queueId: UUID,
    )

    fun findByMessageIdAndQueueId(
        messageId: UUID,
        queueId: UUID,
    ): Message?

    fun findByMessageId(messageId: UUID): Message?

    @Query(
        value = "SELECT COUNT(*) FROM messages WHERE queue_id = CAST(:queueId AS uuid) AND visible_at > :now AND delivery_count > 0",
        nativeQuery = true,
    )
    fun countInFlightMessages(
        @Param("queueId") queueId: UUID,
        @Param("now") now: LocalDateTime,
    ): Long

    @Query(
        value = """
            SELECT CASE 
                WHEN EXISTS(
                    SELECT 1 FROM messages 
                    WHERE queue_id = CAST(:queueId AS uuid) 
                    AND visible_at <= :now 
                    AND delivery_count < :maxDeliveries
                ) THEN 
                    EXTRACT(EPOCH FROM (:now - MIN(created_at)))
                ELSE 0.0 
            END 
            FROM messages 
            WHERE queue_id = CAST(:queueId AS uuid) 
            AND visible_at <= :now 
            AND delivery_count < :maxDeliveries
        """,
        nativeQuery = true,
    )
    fun findOldestWaitingMessageAge(
        @Param("queueId") queueId: UUID,
        @Param("now") now: LocalDateTime,
        @Param("maxDeliveries") maxDeliveries: Int,
    ): Double
}
