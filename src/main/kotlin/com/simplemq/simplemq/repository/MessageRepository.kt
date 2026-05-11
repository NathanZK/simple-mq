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

    @Modifying
    @Query(
        value = "DELETE FROM messages WHERE queue_id = CAST(:queueId AS uuid)",
        nativeQuery = true,
    )
    fun deleteAllByQueueId(
        @Param("queueId") queueId: UUID,
    )

    @Query(
        value = """
            SELECT * FROM messages 
            WHERE queue_id = CAST(:queueId AS uuid)
            AND (
                CAST(:cursorCreatedAt AS timestamp) IS NULL
                OR (
                    CAST(:cursorMessageId AS uuid) IS NOT NULL
                    AND (created_at, message_id) > (CAST(:cursorCreatedAt AS timestamp), CAST(:cursorMessageId AS uuid))
                )
                OR (
                    CAST(:cursorMessageId AS uuid) IS NULL
                    AND created_at > CAST(:cursorCreatedAt AS timestamp)
                )
            )
            ORDER BY created_at ASC, message_id ASC 
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun peekMessages(
        @Param("queueId") queueId: UUID,
        @Param("cursorCreatedAt") cursorCreatedAt: LocalDateTime?,
        @Param("cursorMessageId") cursorMessageId: UUID?,
        @Param("limit") limit: Int,
    ): List<Message>

    @Query(
        value = """
            SELECT COUNT(*) 
            FROM messages m 
            JOIN queues q ON m.queue_id = q.queue_id 
            WHERE m.queue_id = CAST(:queueId AS uuid) 
                AND m.delivery_count >= q.max_deliveries 
                AND m.visible_at <= CAST(:now AS timestamp)
        """,
        nativeQuery = true,
    )
    fun countExhaustedMessages(
        @Param("queueId") queueId: UUID,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        value = """
            WITH queue_config AS (
                -- Fetch config once to use in both selection and update
                SELECT queue_id, max_deliveries, visibility_timeout 
                FROM queues 
                WHERE queue_id = CAST(:queueId AS uuid)
            ),
            locked_message AS (
                SELECT m.message_id
                FROM messages m
                JOIN queue_config c ON m.queue_id = c.queue_id
                WHERE m.queue_id = c.queue_id
                  AND m.visible_at <= :now
                  AND m.delivery_count < c.max_deliveries
                ORDER BY m.created_at ASC 
                LIMIT 1 
                FOR UPDATE SKIP LOCKED
            )
            UPDATE messages m
            SET delivery_count = m.delivery_count + 1,
                visible_at = CAST(:now AS timestamp) + (c.visibility_timeout || ' seconds')::interval
            FROM locked_message l
            JOIN queue_config c ON true
            WHERE m.message_id = l.message_id
            RETURNING m.*
        """,
        nativeQuery = true,
    )
    fun findLockAndUpdateMessageAtomic(
        @Param("queueId") queueId: UUID,
        @Param("now") now: LocalDateTime,
    ): Message?

    @Query(
        value = """
            WITH moved_rows AS (
                DELETE FROM messages
                WHERE message_id IN (
                    SELECT m.message_id 
                    FROM messages m
                    JOIN queues q ON m.queue_id = q.queue_id
                    WHERE m.queue_id = CAST(:sourceQueueId AS uuid) 
                    AND m.delivery_count >= q.max_deliveries
                    AND m.visible_at <= CAST(:now AS timestamp)
                    LIMIT :availableSpace
                )
                RETURNING *
            ),
            insert_step AS (
                INSERT INTO messages (message_id, queue_id, data, delivery_count, visible_at, created_at)
                SELECT message_id, CAST(:dlqId AS uuid), data, delivery_count, CAST(:now AS timestamp), created_at
                FROM moved_rows
            ),
            update_source AS (
                UPDATE queues SET current_message_count = GREATEST(0, current_message_count - (SELECT COUNT(*) FROM moved_rows))
                WHERE queue_id = CAST(:sourceQueueId AS uuid) AND EXISTS (SELECT 1 FROM moved_rows)
            ),
            update_dlq AS (
                UPDATE queues SET current_message_count = current_message_count + (SELECT COUNT(*) FROM moved_rows)
                WHERE queue_id = CAST(:dlqId AS uuid) AND EXISTS (SELECT 1 FROM moved_rows)
            )
            SELECT COUNT(*) FROM moved_rows
        """,
        nativeQuery = true,
    )
    fun moveMessagesToDlqAtomic(
        @Param("sourceQueueId") sourceQueueId: UUID,
        @Param("dlqId") dlqId: UUID,
        @Param("availableSpace") availableSpace: Int,
        @Param("now") now: LocalDateTime,
    ): Int
}
