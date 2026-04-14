# Integration Tests

## Prerequisites
- Docker Desktop must be running

## Running Tests
```bash
./gradlew test --tests "*QueueIntegrationTest"
```

## Test Coverage
- Basic queue operations (create/enqueue/dequeue)
- Empty queue behavior
- Queue capacity limits (429 response)
- Dead Letter Queue routing after max deliveries
