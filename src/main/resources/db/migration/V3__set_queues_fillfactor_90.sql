-- Set FILLFACTOR to 90 for HOT optimization on frequent current_message_count updates
-- Uses explicit schema for determinism across environments
ALTER TABLE public.queues SET (fillfactor = 90);
