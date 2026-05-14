-- Standard Transactional migration
ALTER TABLE public.messages SET (fillfactor = 70);
DROP INDEX IF EXISTS public.idx_messages_poll;
