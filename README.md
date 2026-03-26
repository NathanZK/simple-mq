# simple-mq

A production-grade message queue service inspired by Amazon SQS. The application is intentionally simple — the focus is infrastructure, operations, and architecture decisions made explicitly.

---

## Table of Contents

- [Problem Statement](#problem-statement)
- [Requirements](#requirements)
- [API Design](#api-design)
- [High Level Design](#high-level-design)
- [Deep Dives](#deep-dives)
  - [Storage](#storage)
  - [Schema](#schema)
  - [Indexes](#indexes)
  - [Concurrent Consumers](#concurrent-consumers)
  - [Visibility Timeout](#visibility-timeout)
  - [DLQ Routing](#dlq-routing)
  - [Poll Flow](#poll-flow)
- [Known Limitations](#known-limitations)
- [Future Improvements](#future-improvements)

---

## Problem Statement

A producer generating work faster than a consumer can process it, or sending messages when a consumer is temporarily down, has nowhere to send its messages without a buffer in between. Without a queue, the producer must wait — blocking on the consumer's availability and speed. Slowness and failure propagate upstream.

This queue solves **temporal decoupling**: the producer and consumer do not need to be alive, healthy, or equally fast at the same time. The producer hands off a message to the queue and its responsibility ends. What happens after that — consumer speed, consumer failures, retries — is the queue's problem.

---

## Requirements

### Functional Requirements

- Producer pushes messages to a named queue
- Consumer polls messages from a named queue
- Consumer acknowledges a message after processing
- Acknowledged messages are deleted from the queue
- Unacknowledged messages become visible again after a configurable visibility timeout
- Failed messages (exceeding max delivery attempts) are routed to a Dead Letter Queue (DLQ)
- Messages in the DLQ can be requeued back to the original queue after debugging
- Multiple named queues are supported
- Multiple consumer servers can poll from the same queue (competing consumers)
- No message ordering guarantees

**Out of scope (v1):**
- Multiple consumer groups per queue
- Message ordering
- Exactly-once delivery

### Non-Functional Requirements

- **Durability**: Messages must survive server crashes. A message is considered safe once written to persistent storage.
- **At-least-once delivery**: The system guarantees a message will be delivered at least once. Duplicate delivery is possible; consumers must be idempotent.
- **Observability**: The system exposes the four golden signals — latency, traffic, errors, and saturation — with metrics specific to queue operations.
- **Automated deployment**: The system supports CI/CD with environment separation between staging and production.
- **Free tier constraint**: All infrastructure must operate within the free tier of the chosen cloud provider.

**Deferred non-functional requirements** (constrained by free tier, revisit after infrastructure audit):
- High availability with automatic failover
- Horizontal scalability targets
- Latency SLOs

#### Observability: Four Golden Signals

Each signal covers multiple metrics specific to queue operations:

**Latency**
- Time from producer push to durability acknowledgement
- Time from consumer poll request to message delivery

**Traffic**
- Messages enqueued per second
- Messages dequeued per second
- Acknowledgements per second
- DLQ transfer rate

**Errors**
- Failed enqueue rate
- Failed acknowledgement rate
- Messages routed to DLQ per second
- HTTP 5xx rate on queue endpoints

**Saturation**
- Queue depth per named queue
- Age of oldest unprocessed message
- In-flight message count (delivered but not yet acknowledged)

---

## API Design

### Create Queue

```
POST /api/queues
```

**Request**
```json
{
  "queue_name": "string",
  "queue_size": 1000,
  "visibility_timeout": 30,
  "max_deliveries": 3
}
```

| Field | Description |
|---|---|
| `queue_name` | Human-readable name for the queue |
| `queue_size` | Maximum number of messages the queue can hold |
| `visibility_timeout` | Seconds a message stays invisible after being polled |
| `max_deliveries` | Number of delivery attempts before routing to DLQ |

**Response**
```json
{
  "queue_id": "q_abc123"
}
```

The DLQ is created lazily — only when the first message is routed there. The DLQ ID is discoverable via the queue metadata endpoint.

**Status codes**
- `200` — queue created
- `400` — invalid request

---

### Get Queue Metadata

```
GET /api/queues/{queue_id}
```

**Response**
```json
{
  "queue_id": "q_abc123",
  "queue_name": "orders",
  "queue_size": 1000,
  "visibility_timeout": 30,
  "max_deliveries": 3,
  "current_message_count": 42,
  "dlq_id": "q_xyz789"
}
```

`dlq_id` is `null` if no messages have been routed to the DLQ yet.

**Status codes**
- `200` — success
- `404` — queue not found

---

### Enqueue

```
POST /api/queues/{queue_id}/messages
```

**Request**
```json
{
  "data": "string"
}
```

Message data is an opaque string. The producer is responsible for serialization (e.g. JSON string). The consumer is responsible for deserialization. The queue does not interpret the payload.

**Response**
```json
{
  "message_id": "msg_def456"
}
```

**Status codes**
- `200` — message enqueued
- `429` — queue is full (back pressure); producer should back off and retry
- `404` — queue not found

---

### Poll

```
GET /api/queues/{queue_id}/messages
```

Returns the next available message and marks it invisible for the duration of `visibility_timeout`. If the message is not acknowledged within that window, it becomes visible again and can be picked up by another consumer.

**Response — message available**
```json
{
  "message_id": "msg_def456",
  "data": "string",
  "visible_until": "2024-01-01T00:00:30Z"
}
```

**Response — queue empty**
```json
{
  "message": null
}
```

**Status codes**
- `200` — success (both empty and non-empty responses return 200)
- `404` — queue not found

An empty queue returns `200` with `message: null`, not `404`. The queue exists — it is simply empty. `404` is reserved for queues that do not exist.

---

### Acknowledge

```
DELETE /api/queues/{queue_id}/messages/{message_id}
```

Deletes the message from the queue. This is the consumer's signal that processing is complete.

**Response**
```json
{
  "message_id": "msg_def456",
  "deleted": true
}
```

**Status codes**
- `200` — message deleted
- `404` — message not found

`404` covers both fake message IDs and messages that have already been deleted (e.g. expired into the DLQ, or already acknowledged by another consumer). The queue does not retain deleted messages to provide a more specific error — that would require storing tombstones indefinitely.

---

### Requeue from DLQ

```
POST /api/queues/{dlq_id}/messages/{message_id}/requeue
```

Moves a message from the DLQ back to its parent queue. Resets `delivery_count` to 0 and sets `visible_at` to `NOW()` so it is immediately available for processing.

Used after debugging a failed message and fixing the underlying issue.

**Response**
```json
{
  "message_id": "msg_def456",
  "requeued_to": "q_abc123"
}
```

**Status codes**
- `200` — message requeued
- `404` — message not found

---

## High Level Design

```
Producer ──► Load Balancer ──► Queue Service (instance 1) ──►
                          └──► Queue Service (instance 2) ──► PostgreSQL
Consumer ──► Load Balancer ──► Queue Service (instance 1) ──►
                          └──► Queue Service (instance 2) ──►
```

### Components

**Producer (external)**
The producer is outside the system boundary. It calls the enqueue API and considers its responsibility complete once it receives a `200`. It does not wait for consumer acknowledgement — that is the point of temporal decoupling.

**Consumer (external)**
The consumer is outside the system boundary. It polls the queue, processes messages, and acknowledges. Multiple consumer servers can poll the same queue simultaneously — the queue handles deduplication via row-level locking.

**Queue Service**
A monolith handling all API requests. Contains no background worker — DLQ routing and visibility timeout management happen at poll time (see Deep Dives). Multiple instances run behind a load balancer.

**PostgreSQL**
Persists all messages and queue state. Runs as a separate instance from the queue service so that a queue service crash does not take down storage.

**Load Balancer**
Routes traffic across queue service instances using least-connections routing. Provider-specific implementation determined during infrastructure setup.

### Design Decisions

**Decision: Monolith vs microservices**

Option 1: Monolith — API layer and all queue logic in a single process.

Option 2: Microservices — separate API service, DLQ routing service, visibility timeout service.

Decision: **Monolith.** Microservices introduce network latency and operational complexity between services that provides no benefit at this scale. A monolith is easier to deploy, debug, and reason about. Extract services when there is a concrete reason to, not before.

---

## Deep Dives

### Storage

**Decision: PostgreSQL vs MongoDB vs Cassandra**

Option 1: **PostgreSQL**
- Fits the query patterns exactly (filtering, ordering, multi-column conditions)
- Strong consistency out of the box
- `SELECT FOR UPDATE SKIP LOCKED` — built-in support for queue-like workloads (see Concurrent Consumers)
- Lightweight on free tier compute (lower memory footprint than MongoDB)
- Fully open source (MIT license)
- Fixed schema is a feature here — message structure is well defined and unlikely to change

Option 2: **MongoDB**
- Document store with rich querying and secondary indexes
- Fits the query patterns adequately
- Slightly heavier memory usage on constrained free tier instances
- SSPL license (relevant for some commercial contexts)
- Schema flexibility is not needed — message structure is fixed

Option 3: **Cassandra**
- Built for distributed writes and high write throughput
- Operationally heavy even for a single node
- The write/read ratio is roughly 1:1, not write-heavy — Cassandra's main strength does not apply
- Tunable consistency adds complexity without benefit at this scale

Decision: **PostgreSQL.** The query patterns are relational (range queries, multi-column filtering, ordered reads). `SKIP LOCKED` eliminates the need to implement optimistic concurrency manually. Lower memory footprint fits free tier constraints. MongoDB was a reasonable alternative but offered no advantage given the fixed schema.

Note: Managed database services (e.g. AWS RDS) were considered but rejected. Usage-based billing on managed services creates unpredictable costs — a single load test can generate an unexpected bill. Self-hosted PostgreSQL on a compute instance gives predictable resource usage within free tier limits.

---

### Schema

#### queues

| Column | Type | Description |
|---|---|---|
| `queue_id` | VARCHAR PK | Unique identifier for the queue |
| `queue_name` | VARCHAR | Human-readable name |
| `queue_size` | INT | Maximum number of messages |
| `visibility_timeout` | INT | Seconds a polled message stays invisible |
| `max_deliveries` | INT | Maximum delivery attempts before DLQ routing |
| `current_message_count` | INT | Live count of messages in the queue |
| `parent_queue_id` | VARCHAR FK (nullable) | Set on DLQ rows; references the parent queue |
| `created_at` | TIMESTAMP | Queue creation time |

`parent_queue_id` is `NULL` for regular queues and set to the parent `queue_id` for DLQ queues. This allows the DLQ to be a first-class queue — it uses the same table, the same poll endpoint, and the same message table. No separate DLQ schema is needed.

**Decision: Separate DLQ table vs single queues table**

Option 1: Single table — DLQ rows live alongside regular queue rows. Distinguished by `parent_queue_id`.

Option 2: Separate DLQ table — DLQ queues and regular queues stored in separate tables.

Decision: **Single table.** A DLQ is structurally identical to a regular queue. The only distinction is that it has a parent. Separating them would require coordinating inserts across two tables, duplicate query logic, and additional joins. With a proper index on `queue_id`, query performance is identical regardless of co-location.

**current_message_count consistency**

`current_message_count` is incremented on enqueue and decremented on acknowledgement. Both operations are wrapped in a transaction with the corresponding message insert or delete — either the message and the count update both succeed, or both fail. A failed transaction surfaces as an error to the caller, who retries. This is acceptable under at-least-once delivery semantics.

#### messages

| Column | Type | Description |
|---|---|---|
| `message_id` | VARCHAR PK | Unique identifier for the message |
| `queue_id` | VARCHAR FK | The queue this message belongs to |
| `data` | TEXT | Opaque message payload |
| `delivery_count` | INT | Number of times this message has been delivered |
| `visible_at` | TIMESTAMP | Message is eligible for polling when `visible_at <= NOW()` |
| `created_at` | TIMESTAMP | Message creation time |

**Decision: delivery_count naming and initial value**

`delivery_count` starts at 0 when a message is enqueued. It is incremented on every delivery — including the first. This avoids the need to distinguish first delivery from subsequent deliveries, which would require an additional boolean column.

`retry_count` was considered as the column name but rejected — incrementing on first delivery makes the name semantically incorrect. `delivery_count` accurately reflects what is being measured. The DLQ condition is `delivery_count >= max_deliveries`.

**Decision: visible_at initial value**

When a message is enqueued, `visible_at` is set to `NOW()`. The message is immediately eligible for polling. No separate "enqueued" state is needed.

---

### Indexes

```sql
CREATE INDEX idx_messages_poll
ON messages (queue_id, visible_at, delivery_count, created_at);
```

**Decision: Index strategy**

Option 1: **Single column index on queue_id**
Narrows to the correct queue instantly but leaves PostgreSQL filtering on `visible_at`, `delivery_count`, and sorting by `created_at` in memory. As queue depth grows, this becomes a full scan within the queue. Poll latency degrades proportionally with queue depth.

Option 2: **Four separate indexes** (one per column)
Useful when queries access each column independently. In this system, the poll query always uses all four columns together. Four separate indexes means four index structures to maintain on every insert and delete, with no query performance benefit over a single composite index for the dominant access pattern.

Option 3: **Composite index on (queue_id, visible_at, delivery_count, created_at)**
Matches the exact poll query. PostgreSQL walks the index in column order — `queue_id` eliminates most rows immediately, `visible_at` filters expired timeouts, `delivery_count` filters DLQ candidates, `created_at` satisfies the ORDER BY without a separate sort step. Poll latency stays flat regardless of queue depth.

Decision: **Composite index.** The poll query is the dominant and most frequent access pattern. It always uses all four columns in the same order. A composite index is cheaper to maintain than four separate indexes and delivers better query performance for this specific pattern.

Tradeoff: Composite indexes are not free. Every insert and delete must update the index. For a balanced read/write ratio this is acceptable. Benchmark with `EXPLAIN ANALYZE` if latency optimization becomes a priority — it may be worth dropping to a two-column index on `(queue_id, visible_at)` and accepting in-memory filtering for the remaining columns.

---

### Concurrent Consumers

Multiple consumer servers can poll the same queue simultaneously. Without coordination, two servers could receive the same message.

The queue uses PostgreSQL's `SELECT FOR UPDATE SKIP LOCKED`:

```sql
SELECT * FROM messages
WHERE queue_id = $1
AND visible_at <= NOW()
AND delivery_count < $2
ORDER BY created_at ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

`FOR UPDATE` locks the selected row for the duration of the transaction. `SKIP LOCKED` means if a row is already locked by another transaction, skip it and move to the next eligible row — do not wait.

Without `SKIP LOCKED`, competing consumers would block on each other, serializing throughput. `SKIP LOCKED` allows multiple consumers to make progress simultaneously without coordination overhead.

Reference: [PostgreSQL documentation on SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)

This is one of the strongest arguments for PostgreSQL over document databases for this use case — MongoDB does not have an equivalent primitive. Implementing the same guarantee in MongoDB would require application-level optimistic concurrency.

---

### Visibility Timeout

When a consumer polls a message, `visible_at` is set to `NOW() + visibility_timeout`. The message disappears from the queue for the duration of the timeout.

If the consumer acknowledges the message, it is deleted. If the consumer crashes, is slow, or fails to acknowledge — the message resurfaces automatically when `visible_at <= NOW()` becomes true again. No background worker is needed. No explicit reset is required. The next poll query picks it up.

When a message resurfaces, `delivery_count` is incremented on the next delivery. If `delivery_count >= max_deliveries`, it is routed to the DLQ at poll time (see DLQ Routing).

---

### DLQ Routing

**Decision: Background worker vs routing at poll time**

Option 1: **Routing at poll time**
When a consumer polls, the queue first moves any eligible messages (those where `delivery_count >= max_deliveries` and `visible_at <= NOW()`) to the DLQ, then fetches the next valid message. No background worker. Two queries per poll request.

Tradeoff: Messages only reach the DLQ when a consumer is actively polling. If there are no active consumers, messages remain in the main queue past their delivery limit. Minor additional latency per poll request.

Option 2: **Background worker**
A dedicated process runs on a schedule and moves expired messages to the DLQ independently of polling activity.

Tradeoff: Adds operational complexity — the worker must be deployed, monitored, and its run frequency tuned. Run it too infrequently and the DLQ is stale anyway; run it too frequently and it consumes free tier resources. Critically: even with a background worker, there is a window between runs where a message that should be in the DLQ is still visible to consumers. The fundamental problem exists in both options.

Decision: **Routing at poll time.** The stale DLQ window exists in both approaches. The background worker adds resource cost and operational complexity with no meaningful improvement to the core problem. Given that free tier resources are constrained and latency is not the primary non-functional requirement, polling-time routing wins. Revisit if latency optimization becomes a priority — at that point, a background worker could be justified to remove the two-query overhead from the poll path.

**DLQ creation**

The DLQ is created lazily — only when the first message is routed there. Creating it eagerly on queue creation allocates resources for a failure path that may never be used. The DLQ ID is returned by the queue metadata endpoint once it exists.

---

### Poll Flow

The complete sequence of operations for a single poll request, executed as one atomic transaction:

1. **Route expired messages to DLQ**

   ```sql
   -- Fetch DLQ queue_id for this queue
   SELECT queue_id FROM queues WHERE parent_queue_id = $queue_id;

   -- Move eligible messages
   UPDATE messages
   SET queue_id = $dlq_queue_id
   WHERE queue_id = $queue_id
   AND visible_at <= NOW()
   AND delivery_count >= $max_deliveries;
   ```

   If the DLQ does not exist yet, create it first (lazy creation).

   `delivery_count` is intentionally not reset when routing to the DLQ. The count reflects how many delivery attempts were made before failure — useful for debugging. The DLQ is a diagnostic tool; preserving the delivery history is intentional.

2. **Fetch the next valid message**

   ```sql
   SELECT * FROM messages
   WHERE queue_id = $queue_id
   AND visible_at <= NOW()
   AND delivery_count < $max_deliveries
   ORDER BY created_at ASC
   LIMIT 1
   FOR UPDATE SKIP LOCKED;
   ```

3. **Increment delivery_count and set visibility timeout**

   ```sql
   UPDATE messages
   SET delivery_count = delivery_count + 1,
       visible_at = NOW() + INTERVAL '$visibility_timeout seconds'
   WHERE message_id = $message_id;
   ```

4. **Return message to consumer**

   Return `message_id`, `data`, and `visible_until` timestamp.

If step 2 returns no rows, return `{ "message": null }` with status `200`.

**DLQ full behavior**

If the DLQ has reached `queue_size`, messages that would be routed to the DLQ remain in the main queue. This causes main queue depth to grow, which triggers the saturation metric and alerts the operator. This is intentional — it forces the issue to be visible rather than silently dropping messages. The operator must drain the DLQ before the system returns to normal operation.

---

## Known Limitations

- **Single PostgreSQL instance**: PostgreSQL is a single point of failure in v1. If the database goes down, the queue is unavailable. A primary-replica setup with automatic failover is the path to high availability but requires additional compute beyond free tier.

- **No ordering guarantees**: Messages are returned in approximate creation order but this is not guaranteed under concurrent load. Strict FIFO ordering at scale requires coordination that significantly reduces throughput.

- **DLQ only updated on active polling**: If no consumers are polling, messages exceeding `max_deliveries` remain in the main queue until the next poll. This is a known tradeoff of routing at poll time vs a background worker.

- **No batching**: Poll returns one message per request. Batch polling (`?limit=N`) is a planned extension.

- **No queue limits per operator**: The number of queues an operator can create is currently unlimited. Rate limiting is a planned extension.

---

## Deployment

### CI/CD

This project uses GitHub Actions for continuous integration and deployment:

- **CI**: Runs on pull requests to `main` - executes tests and code style checks
- **CD**: Runs on pushes to any branch - builds and pushes Docker images

#### Required GitHub Secrets

Set these in your GitHub repository settings:

- `DOCKERHUB_USERNAME`: Your Docker Hub username
- `DOCKERHUB_TOKEN`: Your Docker Hub access token

#### Docker Images

Images are automatically built and pushed to Docker Hub:

- `nathanzk/simple-mq:{short-sha}`: Tagged with commit SHA
- `nathanzk/simple-mq:latest`: Latest version (only on main branch)

#### Manual Docker Build

```bash
# Build for production
docker build -t nathanzk/simple-mq:latest .

# Run the container
docker run -p 8080:8080 nathanzk/simple-mq:latest
```

---

## Future Improvements

- PostgreSQL primary-replica with automatic failover
- Batch polling (`GET /api/queues/{queue_id}/messages?limit=N`)
- Queue creation rate limiting per operator
- Queue size enforcement with configurable back-pressure behavior
- Background worker for DLQ routing (evaluate against polling-time latency benchmarks)
- Message ordering (FIFO queue type with tradeoff documentation)
- Metrics endpoint (`/metrics`) for Prometheus scraping
