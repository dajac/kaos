/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { ConsumerSnapshot, GroupSnapshot, Snapshot } from '../api/client';

// Snapshots for the tests: the group `orders` on two topics of four
// partitions, with a consumer-protocol consumer and a classic one.

export function consumer(id: string, overrides: Partial<ConsumerSnapshot> = {}): ConsumerSnapshot {
  return {
    id,
    clientId: `orders-${id}`,
    protocol: 'consumer',
    subscribed: true,
    faults: [],
    memberId: `member-${id}`,
    instanceId: '',
    memberEpoch: 5,
    generation: -1,
    coordinatorState: 'STABLE',
    status: 'STABLE',
    failure: '',
    partitions: [],
    raw: { assignment: [], targetAssignment: [], localAssignment: [] },
    ...overrides,
  };
}

export function group(overrides: Partial<GroupSnapshot> = {}): GroupSnapshot {
  return {
    id: 'orders',
    type: 'CONSUMER',
    state: 'STABLE',
    epoch: 7,
    targetAssignmentEpoch: 7,
    generation: -1,
    subscribedTopics: ['orders-1', 'orders-2'],
    lag: { 'orders-1': [0, 12, -1, 3], 'orders-2': [0, 0, 0, 0] },
    consumers: [
      consumer('consumer-0', {
        partitions: [
          { topic: 'orders-1', partition: 0, state: 'ASSIGNED' },
          { topic: 'orders-1', partition: 1, state: 'PENDING' },
          { topic: 'orders-2', partition: 3, state: 'REVOKING' },
        ],
      }),
      consumer('consumer-1', { protocol: 'classic', memberEpoch: -1, generation: 4 }),
    ],
    ...overrides,
  };
}

export function snapshot(overrides: Partial<Snapshot> = {}): Snapshot {
  return {
    revision: 1,
    phase: 'RUNNING',
    stale: false,
    lastError: '',
    topics: [
      { name: 'orders-1', partitions: 4 },
      { name: 'orders-2', partitions: 4 },
    ],
    producers: [{ id: 'producer-0', topics: ['orders-1', 'orders-2'], state: 'RUNNING', ratePerPartition: 2 }],
    groups: [group()],
    ...overrides,
  };
}
