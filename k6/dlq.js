import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://34.59.35.212:8080';

export const options = {
  vus: 1,
  iterations: 1,
};

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function () {
  // Step 1: Create queue with maxDeliveries=1 and visibilityTimeout=1
  const createRes = http.post(
      `${BASE_URL}/api/queues`,
      JSON.stringify({
        queueName: `synthetic-dlq-${Date.now()}`,
        queueSize: 100,
        visibilityTimeout: 1,
        maxDeliveries: 1,
      }),
      { headers: { 'Content-Type': 'application/json' } }
  );

  check(createRes, {
    'create queue status 201': (r) => r.status === 201,
    'create queue has queue_id': (r) => JSON.parse(r.body).queue_id !== undefined,
  });

  const queueId = JSON.parse(createRes.body).queue_id;

  // Step 2: GET queue metadata — assert dlq_id is null
  const metaRes1 = http.get(`${BASE_URL}/api/queues/${queueId}`);

  check(metaRes1, {
    'initial dlq_id is null': (r) => JSON.parse(r.body).dlq_id === null,
  });

  // Step 3: Enqueue a message
  const enqueueRes = http.post(
      `${BASE_URL}/api/queues/${queueId}/messages`,
      JSON.stringify({ data: 'synthetic-dlq-test-message' }),
      { headers: { 'Content-Type': 'application/json' } }
  );

  check(enqueueRes, {
    'enqueue status 201': (r) => r.status === 201,
  });

  // Step 4: Dequeue — don't ACK
  const dequeueRes1 = http.get(`${BASE_URL}/api/queues/${queueId}/messages`);

  check(dequeueRes1, {
    'dequeue status 200': (r) => r.status === 200,
    'dequeue message is not null': (r) => JSON.parse(r.body).message !== null,
  });

  // Step 5: Sleep for visibility timeout to expire
  sleep(2);

  // Step 6: Dequeue again — triggers DLQ creation, expect empty
  const dequeueRes2 = http.get(`${BASE_URL}/api/queues/${queueId}/messages`);

  check(dequeueRes2, {
    'second dequeue status 200': (r) => r.status === 200,
    'second dequeue message is null': (r) => JSON.parse(r.body).message === null,
  });

  // Step 7: GET queue metadata — assert dlq_id is now set
  const metaRes2 = http.get(`${BASE_URL}/api/queues/${queueId}`);

  check(metaRes2, {
    'dlq_id is now set': (r) => JSON.parse(r.body).dlq_id !== null,
    'dlq_id is valid UUID': (r) => UUID_REGEX.test(JSON.parse(r.body).dlq_id),
  });

  const dlqId = JSON.parse(metaRes2.body).dlq_id;

  // Step 8: Check DLQ has messages via metadata
  const dlqMetaRes = http.get(`${BASE_URL}/api/queues/${dlqId}`);

  console.log('dlq metadata response:', dlqMetaRes.body);

  check(dlqMetaRes, {
    'dlq metadata status 200': (r) => r.status === 200,
    'dlq has messages': (r) => JSON.parse(r.body).current_message_count > 0,
  });

  // Step 9: Delete original queue first (has FK reference to DLQ)
  const deleteQueueRes = http.del(`${BASE_URL}/api/queues/${queueId}`);

  console.log('delete queue response:', deleteQueueRes.body);

  check(deleteQueueRes, {
    'delete queue status 200': (r) => r.status === 200,
  });

  // Step 10: Delete DLQ
  const deleteDlqRes = http.del(`${BASE_URL}/api/queues/${dlqId}`);

  console.log('delete dlq response:', deleteDlqRes.body);

  check(deleteDlqRes, {
    'delete dlq status 200': (r) => r.status === 200,
  });
}