# SimpleMQ API Documentation

This document provides comprehensive API documentation for the SimpleMQ message queue service.

## Base URL
```
http://localhost:8080/api/v1/queues
```

**Note:** Consider implementing API versioning (`/api/v1/`) for future compatibility and authentication for production use.

## Overview
SimpleMQ provides a RESTful API for managing message queues and handling message operations. The API supports creating queues, enqueueing/dequeueing messages, and managing message lifecycle.

---

## Endpoints

### 1. Create Queue

**POST** `/api/queues`

Creates a new message queue with specified configuration.

#### Request Body
```json
{
  "queueName": "string",
  "queueSize": "integer",
  "visibilityTimeout": "integer",
  "maxDeliveries": "integer"
}
```

#### Parameters
- `queueName` (string, required): Name of the queue (1-255 characters)
- `queueSize` (integer, required): Maximum number of messages in queue (1-1,000,000)
- `visibilityTimeout` (integer, required): Time in seconds before message becomes visible again (0-43200)
- `maxDeliveries` (integer, required): Maximum delivery attempts before message goes to DLQ (1-100)

#### Response
```json
{
  "queue_id": "uuid"
}
```

#### Example Request
```bash
curl -X POST http://localhost:8080/api/queues \
  -H "Content-Type: application/json" \
  -d '{
    "queueName": "order-queue",
    "queueSize": 10000,
    "visibilityTimeout": 300,
    "maxDeliveries": 5
  }'
```

#### Example Response
```json
{
  "queue_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### HTTP Status Codes
- `201 Created`: Queue successfully created
- `400 Bad Request`: Invalid request parameters
- `409 Conflict`: Queue with same name already exists

---

### 2. Get Queue Metadata

**GET** `/api/queues/{queue_id}`

Retrieves metadata and current status of a specific queue.

#### Path Parameters
- `queue_id` (string, required): UUID of the queue

#### Response
```json
{
  "queue_id": "uuid",
  "queue_name": "string",
  "queue_size": "integer",
  "visibility_timeout": "integer",
  "max_deliveries": "integer",
  "current_message_count": "integer",
  "dlq_id": "uuid|null"
}
```

#### Example Request
```bash
curl -X GET http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000
```

#### Example Response
```json
{
  "queue_id": "550e8400-e29b-41d4-a716-446655440000",
  "queue_name": "order-queue",
  "queue_size": 10000,
  "visibility_timeout": 300,
  "max_deliveries": 5,
  "current_message_count": 42,
  "dlq_id": "660e8400-e29b-41d4-a716-446655440001"
}
```

#### HTTP Status Codes
- `200 OK`: Queue metadata retrieved successfully
- `404 Not Found`: Queue not found

---

### 3. Enqueue Message

**POST** `/api/queues/{queue_id}/messages`

Adds a new message to the specified queue.

#### Path Parameters
- `queue_id` (string, required): UUID of the queue

#### Request Body
```json
{
  "data": "string"
}
```

#### Parameters
- `data` (string, required): Message content/payload

#### Response
```json
{
  "message_id": "uuid"
}
```

#### Example Request
```bash
curl -X POST http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages \
  -H "Content-Type: application/json" \
  -d '{
    "data": "{\"orderId\": \"12345\", \"amount\": 99.99, \"customer\": \"John Doe\"}"
  }'
```

#### Example Response
```json
{
  "message_id": "770e8400-e29b-41d4-a716-446655440002"
}
```

#### HTTP Status Codes
- `201 Created`: Message successfully enqueued
- `400 Bad Request`: Invalid request data
- `404 Not Found`: Queue not found
- `409 Conflict`: Queue is full

---

### 4. Dequeue Message

**GET** `/api/queues/{queue_id}/messages`

Retrieves and locks the next available message from the queue.

#### Path Parameters
- `queue_id` (string, required): UUID of the queue

#### Response
```json
{
  "message": {
    "message_id": "uuid",
    "data": "string",
    "invisible_until": "datetime"
  } | null
}
```

#### Example Request
```bash
curl -X GET http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages
```

#### Example Response (Message Available)
```json
{
  "message": {
    "message_id": "770e8400-e29b-41d4-a716-446655440002",
    "data": "{\"orderId\": \"12345\", \"amount\": 99.99, \"customer\": \"John Doe\"}",
    "invisible_until": "2024-03-28T15:30:00"
  }
}
```

#### Example Response (No Messages)
```json
{
  "message": null
}
```

#### HTTP Status Codes
- `200 OK`: Request processed successfully
- `404 Not Found`: Queue not found

#### Notes
- The message becomes invisible for the queue's `visibilityTimeout` period
- If not processed and deleted within this time, the message becomes visible again
- Messages that exceed `maxDeliveries` are moved to the Dead Letter Queue (DLQ)

---

### 5. Delete Message

**DELETE** `/api/queues/{queue_id}/messages/{message_id}`

Permanently deletes a message from the queue (typically after successful processing).

#### Path Parameters
- `queue_id` (string, required): UUID of the queue
- `message_id` (string, required): UUID of the message to delete

#### Response
Empty response with HTTP status 200

#### Example Request
```bash
curl -X DELETE http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages/770e8400-e29b-41d4-a716-446655440002
```

#### HTTP Status Codes
- `200 OK`: Message successfully deleted
- `404 Not Found`: Queue or message not found

---

### 6. Peek Messages

**GET** `/api/queues/{queue_id}/messages/peek`

Retrieves messages from the queue without dequeuing or locking them, supporting cursor-based pagination for large queues.

#### Path Parameters
- `queue_id` (string, required): UUID of the queue

#### Query Parameters
- `limit` (integer, required): Maximum number of messages to return (max: 100)
- `cursorCreatedAt` (string, optional): ISO 8601 datetime string for pagination - returns messages created after this timestamp
- `cursorMessageId` (string, optional): UUID of the last message from the previous page - used with cursorCreatedAt for stable pagination

#### Cursor Logic
- **Neither provided**: Fetch from oldest message
- **Both provided**: Full composite cursor - returns messages where `(created_at, message_id) > (cursorCreatedAt, cursorMessageId)` (stable pagination)
- **Only cursorCreatedAt provided**: Returns messages where `created_at > cursorCreatedAt` (slight skip risk at boundary if multiple messages share same timestamp)
- **Only cursorMessageId provided**: Ignored, treated as no cursor

#### Response
```json
{
  "messages": [
    {
      "message_id": "uuid",
      "data": "string",
      "delivery_count": "integer",
      "invisible_until": "datetime",
      "created_at": "datetime"
    }
  ],
  "nextCursorCreatedAt": "iso8601-instant|null",
  "nextCursorMessageId": "uuid|null"
}
```

#### Example Request (First Page)
```bash
curl -X GET "http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages/peek?limit=10"
```

#### Example Request (With Cursor - Stable Pagination)
```bash
curl -X GET "http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages/peek?limit=10&cursorCreatedAt=2024-03-28T15:26:00&cursorMessageId=880e8400-e29b-41d4-a716-446655440003"
```

#### Example Response (Messages Available)
```json
{
  "messages": [
    {
      "message_id": "770e8400-e29b-41d4-a716-446655440002",
      "data": "{\"orderId\": \"12345\", \"amount\": 99.99}",
      "delivery_count": 2,
      "invisible_until": "2024-03-28T15:35:00",
      "created_at": "2024-03-28T15:25:00"
    },
    {
      "message_id": "880e8400-e29b-41d4-a716-446655440003",
      "data": "{\"orderId\": \"12346\", \"amount\": 149.99}",
      "delivery_count": 0,
      "invisible_until": "2024-03-28T15:40:00",
      "created_at": "2024-03-28T15:26:00"
    }
  ],
  "nextCursorCreatedAt": "2024-03-28T15:26:00Z",
  "nextCursorMessageId": "880e8400-e29b-41d4-a716-446655440003"
}
```

#### Example Response (End of Queue)
```json
{
  "messages": [
    {
      "message_id": "990e8400-e29b-41d4-a716-446655440004",
      "data": "{\"orderId\": \"12347\", \"amount\": 199.99}",
      "delivery_count": 1,
      "invisible_until": "2024-03-28T15:45:00",
      "created_at": "2024-03-28T15:27:00"
    }
  ],
  "nextCursorCreatedAt": null,
  "nextCursorMessageId": null
}
```

#### Example Response (Empty Queue)
```json
{
  "messages": [],
  "nextCursorCreatedAt": null,
  "nextCursorMessageId": null
}
```

#### HTTP Status Codes
- `200 OK`: Messages retrieved successfully
- `404 Not Found`: Queue not found
- `400 Bad Request`: Invalid cursor format, invalid UUID format, or limit value (must be > 0 and <= 100)

#### Notes
- Messages are returned in ascending order by creation timestamp, then by message ID
- For stable pagination, provide both `cursorCreatedAt` and `cursorMessageId` from the last message in the previous page
- `nextCursorCreatedAt` and `nextCursorMessageId` are both `null` when there are no more messages to retrieve
- Unlike dequeue, this operation does not lock messages or affect their visibility
- Useful for message inspection, debugging, and monitoring without affecting message availability

---

### 7. Requeue Message

**POST** `/api/queues/{queue_id}/messages/{message_id}/requeue`

Returns a message to the queue for reprocessing (useful for handling transient failures).

#### Path Parameters
- `queue_id` (string, required): UUID of the queue
- `message_id` (string, required): UUID of the message to requeue

#### Response
```json
{
  "message_id": "uuid"
}
```

#### Example Request
```bash
curl -X POST http://localhost:8080/api/queues/550e8400-e29b-41d4-a716-446655440000/messages/770e8400-e29b-41d4-a716-446655440002/requeue
```

#### Example Response
```json
{
  "message_id": "770e8400-e29b-41d4-a716-446655440002"
}
```

#### HTTP Status Codes
- `200 OK`: Message successfully requeued
- `404 Not Found`: Queue or message not found

---

## Common Workflows

### Basic Message Processing Flow

1. **Create a queue:**
```bash
curl -X POST http://localhost:8080/api/queues \
  -H "Content-Type: application/json" \
  -d '{
    "queueName": "task-queue",
    "queueSize": 5000,
    "visibilityTimeout": 600,
    "maxDeliveries": 3
  }'
```

2. **Enqueue a message:**
```bash
curl -X POST http://localhost:8080/api/queues/{queue_id}/messages \
  -H "Content-Type: application/json" \
  -d '{"data": "Process order #12345"}'
```

3. **Dequeue and process:**
```bash
curl -X GET http://localhost:8080/api/queues/{queue_id}/messages
```

4. **Delete after successful processing:**
```bash
curl -X DELETE http://localhost:8080/api/queues/{queue_id}/messages/{message_id}
```

### Dead Letter Queue (DLQ) Workflow

When messages exceed their `maxDeliveries` count, they're automatically moved to a DLQ:

1. **Create a queue with low retry count:**
```bash
curl -X POST http://localhost:8080/api/queues \
  -H "Content-Type: application/json" \
  -d '{
    "queueName": "critical-tasks",
    "queueSize": 1000,
    "visibilityTimeout": 1,
    "maxDeliveries": 1
  }'
```

2. **Enqueue a message:**
```bash
curl -X POST http://localhost:8080/api/queues/{queue_id}/messages \
  -H "Content-Type: application/json" \
  -d '{"data": "Process critical task"}'
```

3. **Dequeue but don't delete (simulates failure):**
```bash
curl -X GET http://localhost:8080/api/queues/{queue_id}/messages
```

4. **Wait for timeout, then retry (goes to DLQ):**
```bash
sleep 2  # Wait for visibility timeout
curl -X GET http://localhost:8080/api/queues/{queue_id}/messages
```

5. **Check if DLQ was created:**
```bash
curl -X GET http://localhost:8080/api/queues/{queue_id} | jq '.dlq_id'
```

6. **Access messages in DLQ:**
```bash
curl -X GET http://localhost:8080/api/queues/{dlq_id}/messages
```

---

## Error Responses

All endpoints may return standard HTTP error responses:

### 400 Bad Request
```json
{
  "timestamp": "2024-03-28T14:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Queue name is required",
  "path": "/api/queues"
}
```

### 404 Not Found
```json
{
  "timestamp": "2024-03-28T14:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Queue not found",
  "path": "/api/queues/invalid-id"
}
```

### 500 Internal Server Error
```json
{
  "timestamp": "2024-03-28T14:30:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/api/queues"
}
```

---
