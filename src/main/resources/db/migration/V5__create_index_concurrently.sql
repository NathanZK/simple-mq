-- flyway:noTransaction=true
-- Clean up zombie index from the failed V4 attempt
DROP INDEX IF EXISTS public.idx_messages_poll_lean;
-- Create the new one
CREATE INDEX CONCURRENTLY idx_messages_poll_lean 
    ON public.messages (queue_id, created_at ASC);
-- Maintenance (These are also non-transactional)
VACUUM public.messages;
ANALYZE public.messages;
