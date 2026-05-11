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

    /**
     * Atomically gets or creates a DLQ for the given source queue and updates parent dlq_id.
     *
     * CONCURRENCY DESIGN: Implements "Zero-Chatter" pattern - returns null when source
     * queue doesn't exist rather than throwing exceptions, preventing cascading failures
     * in high-throughput scenarios.
     *
     * CAPACITY TRADE-OFF: DLQ capacity limits are "soft targets" to prioritize source
     * queue availability. Under high concurrent load, temporary capacity overshoot is
     * acceptable to ensure "poison messages" are evicted without blocking primary workers.
     *
     * @param sourceQueueId The source queue identifier (must exist)
     * @param dlqId The deterministic UUID to use for the DLQ
     * @return The DLQ entity (existing or newly created), or null if source queue doesn't exist
     */
    @Query(
        value = """
            WITH parent AS (
                SELECT * FROM queues WHERE queue_id = CAST(:sourceQueueId AS uuid)
            ),
            inserted AS (
                INSERT INTO queues (queue_id, queue_name, queue_size, visibility_timeout, max_deliveries, current_message_count, created_at)
                SELECT CAST(:dlqId AS uuid), queue_name || '-dlq', queue_size, visibility_timeout, max_deliveries, 0, NOW()
                FROM parent
                ON CONFLICT (queue_id) DO NOTHING
                RETURNING *
            ),
            updated AS (
                UPDATE queues SET dlq_id = CAST(:dlqId AS uuid) 
                WHERE queue_id = CAST(:sourceQueueId AS uuid) AND dlq_id IS NULL
            )
            SELECT * FROM inserted
            UNION ALL
            SELECT * FROM queues WHERE queue_id = CAST(:dlqId AS uuid) AND NOT EXISTS (SELECT 1 FROM inserted);
        """,
        nativeQuery = true,
    )
    fun getOrCreateDlqAndUpdateParent(
        @Param("sourceQueueId") sourceQueueId: UUID,
        @Param("dlqId") dlqId: UUID,
    ): Queue?
}
