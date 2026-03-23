CREATE TABLE queues (
    queue_id UUID PRIMARY KEY,
    queue_name VARCHAR NOT NULL,
    queue_size INTEGER NOT NULL,
    visibility_timeout INTEGER NOT NULL,
    max_deliveries INTEGER NOT NULL,
    current_message_count INTEGER NOT NULL DEFAULT 0,
    parent_queue_id UUID REFERENCES queues(queue_id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE messages (
    message_id UUID PRIMARY KEY,
    queue_id UUID NOT NULL REFERENCES queues(queue_id),
    data TEXT NOT NULL,
    delivery_count INTEGER NOT NULL DEFAULT 0,
    visible_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_poll
ON messages (queue_id, visible_at, delivery_count, created_at);
