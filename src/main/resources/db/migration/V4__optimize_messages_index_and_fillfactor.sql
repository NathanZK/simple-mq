-- flyway:noTransaction=true
-- 1. Optimized Index for Polling
-- We use a "Lean" index to enable HOT updates.
-- Includes only the sort/partition columns.
DROP INDEX IF EXISTS public.idx_messages_poll;
CREATE INDEX CONCURRENTLY idx_messages_poll_lean
    ON public.messages (queue_id, created_at ASC);

-- 2. Configure Table for Write Efficiency
-- Leave 30% space on each page for in-place (HOT) updates.
ALTER TABLE public.messages SET (fillfactor = 70);

-- 3. Apply changes to existing data
-- This physically rewrites the table to apply the fillfactor gaps.
VACUUM public.messages;

-- 4. Refresh Statistics for the Planner
ANALYZE public.messages;