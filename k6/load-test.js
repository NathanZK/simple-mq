import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://34.59.35.212:8080';
const QUEUE_COUNT = 15;
const TEST_DURATION = '3m';
const VUS = 10;

export const options = {
    vus: VUS,
    duration: TEST_DURATION,
};

export function setup() {
    const queueIds = [];

    for (let i = 0; i < QUEUE_COUNT; i++) {
        const res = http.post(
            `${BASE_URL}/api/queues`,
            JSON.stringify({
                queueName: `load-test-queue-${i}`,
                queueSize: 10000,
                visibilityTimeout: 30,
                maxDeliveries: 3,
            }),
            {
                headers: { 'Content-Type': 'application/json' },
                tags: { name: 'create_queue' },
            }
        );

        check(res, {
            [`setup: created queue ${i}`]: (r) => r.status === 201,
        });

        queueIds.push(JSON.parse(res.body).queue_id);
    }

    console.log(`Setup complete — created ${queueIds.length} queues`);
    return { queueIds };
}

export default function (data) {
    const queueIds = data.queueIds;
    const queueId = queueIds[Math.floor(Math.random() * queueIds.length)];

    // Step 1: Enqueue
    const enqueueRes = http.post(
        `${BASE_URL}/api/queues/${queueId}/messages`,
        JSON.stringify({ data: `load-test-message-${Date.now()}` }),
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'enqueue' },
        }
    );

    const enqueueOk = check(enqueueRes, {
        'enqueue status 201': (r) => r.status === 201,
    });

    if (!enqueueOk) return;

    const messageId = JSON.parse(enqueueRes.body).message_id;

    // Step 2: Dequeue
    const dequeueRes = http.get(
        `${BASE_URL}/api/queues/${queueId}/messages`,
        { tags: { name: 'dequeue' } }
    );

    const dequeueOk = check(dequeueRes, {
        'dequeue status 200': (r) => r.status === 200,
        'dequeue message not null': (r) => JSON.parse(r.body).message !== null,
    });

    if (!dequeueOk) return;

    const dequeuedMessageId = JSON.parse(dequeueRes.body).message?.message_id;
    if (!dequeuedMessageId) return;

    // Step 3: ACK
    const ackRes = http.del(
        `${BASE_URL}/api/queues/${queueId}/messages/${dequeuedMessageId}`,
        null,
        { tags: { name: 'ack' } }
    );

    check(ackRes, {
        'ack status 200': (r) => r.status === 200,
    });
}

export function teardown(data) {
    const queueIds = data.queueIds;

    for (const queueId of queueIds) {
        const res = http.del(
            `${BASE_URL}/api/queues/${queueId}`,
            null,
            { tags: { name: 'delete_queue' } }
        );
        console.log(`delete queue ${queueId}: ${res.status} ${res.body}`);
        check(res, {
            [`teardown: deleted queue ${queueId}`]: (r) => r.status === 200,
        });
    }

    console.log(`Teardown complete — deleted ${queueIds.length} queues`);
}