import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const QUEUE_COUNT = 15;
const TEST_DURATION = '3m';
const VUS = 10;

export const options = {
    vus: VUS,
    duration: TEST_DURATION,
    thresholds: {
        // Overall error rate
        http_req_failed: ['rate<0.01'],

        // Overall latency
        'http_req_duration': ['p(95)<600', 'p(99)<1200'],

        // Per-endpoint latency
        'http_req_duration{name:enqueue}': ['p(95)<700', 'p(99)<1200'],
        'http_req_duration{name:dequeue}': ['p(95)<600'],
        'http_req_duration{name:ack}':     ['p(95)<400'],

        // Throughput floor
        'http_reqs': ['rate>20'],
    },
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

        try {
            const response = JSON.parse(res.body);
            queueIds.push(response.queue_id);
        } catch (e) {
            console.error(`Failed to parse queue creation response: ${res.body}`);
            console.error(`Error: ${e.message}`);
            throw e;
        }
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

        try {
            var messageId = JSON.parse(enqueueRes.body).message_id;
        } catch (e) {
            console.error(`Failed to parse enqueue response: ${enqueueRes.body}`);
            console.error(`Error: ${e.message}`);
            return;
        }

    // Step 2: Dequeue
    const dequeueRes = http.get(
        `${BASE_URL}/api/queues/${queueId}/messages`,
        { tags: { name: 'dequeue' } }
    );

    const dequeueOk = check(dequeueRes, {
        'dequeue status 200': (r) => r.status === 200,
        'dequeue message not null': (r) => {
            try {
                return JSON.parse(r.body).message !== null;
            } catch (e) {
                console.error(`Failed to parse dequeue response: ${r.body}`);
                return false;
            }
        },
    });

    if (!dequeueOk) return;

    let dequeuedMessageId;
    try {
        dequeuedMessageId = JSON.parse(dequeueRes.body).message?.message_id;
    } catch (e) {
        console.error(`Failed to parse dequeued message ID: ${dequeueRes.body}`);
        return;
    }
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