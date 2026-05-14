-- flyway:noTransaction=true
CREATE INDEX CONCURRENTLY idx_messages_poll_lean 
    ON public.messages (queue_id, created_at ASC);
-- Maintenance (These are also non-transactional)
VACUUM public.messages;
ANALYZE public.messages;
