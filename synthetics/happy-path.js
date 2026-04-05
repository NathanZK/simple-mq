import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://34.59.35.212:8080';

export const options = {
  vus: 1,
  iterations: 1,
};

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function () {
  // Step 1: Create queue
  const createRes = http.post(
      `${BASE_URL}/api/queues`,
      JSON.stringify({
        queueName: `synthetic-happy-${Date.now()}`,
        queueSize: 100,
        visibilityTimeout: 30,
        maxDeliveries: 3,
      }),
      { headers: { 'Content-Type': 'application/json' } }
  );

  console.log('create queue response:', createRes.body);

  check(createRes, {
    'create queue status 201': (r) => r.status === 201,
    'create queue has queue_id': (r) => JSON.parse(r.body).queue_id !== undefined,
    'create queue_id is valid UUID': (r) => UUID_REGEX.test(JSON.parse(r.body).queue_id),
  });

  const queueId = JSON.parse(createRes.body).queue_id;

  // Step 2: Enqueue message
  const enqueueRes = http.post(
      `${BASE_URL}/api/queues/${queueId}/messages`,
      JSON.stringify({ data: 'synthetic-test-message' }),
      { headers: { 'Content-Type': 'application/json' } }
  );

  console.log('enqueue response:', enqueueRes.body);

  check(enqueueRes, {
    'enqueue status 201': (r) => r.status === 201,
    'enqueue has message_id': (r) => JSON.parse(r.body).message_id !== undefined,
    'enqueue message_id is valid UUID': (r) => UUID_REGEX.test(JSON.parse(r.body).message_id),
  });

  const messageId = JSON.parse(enqueueRes.body).message_id;

  // Step 3: Dequeue message
  const dequeueRes = http.get(`${BASE_URL}/api/queues/${queueId}/messages`);

  console.log('dequeue response:', dequeueRes.body);

  check(dequeueRes, {
    'dequeue status 200': (r) => r.status === 200,
    'dequeue message is not null': (r) => JSON.parse(r.body).message !== null,
    'dequeue message_id matches': (r) => JSON.parse(r.body).message.message_id === messageId,
    'dequeue data matches': (r) => JSON.parse(r.body).message.data === 'synthetic-test-message',
    'dequeue invisible_until is future': (r) => new Date(JSON.parse(r.body).message.invisible_until + 'Z') > new Date(),
  });

  // Step 4: ACK message
  const ackRes = http.del(`${BASE_URL}/api/queues/${queueId}/messages/${messageId}`);

  console.log('ack response:', ackRes.body);

  check(ackRes, {
    'ack status 200': (r) => r.status === 200,
  });

  // Step 5: Delete queue
  const deleteQueueRes = http.del(`${BASE_URL}/api/queues/${queueId}`);

  console.log('delete queue response:', deleteQueueRes.body);

  check(deleteQueueRes, {
    'delete queue status 200': (r) => r.status === 200,
  });
}