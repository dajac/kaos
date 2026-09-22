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

import type { components } from '../api/schema';

// The body of `GET /api/v1/meta` for the tests: the table of `ConfigMetadata`.

type ConfigField = components['schemas']['ConfigField'];

const int = (
  key: string,
  label: string,
  scope: ConfigField['scope'],
  defaultValue: number,
  min: number,
  max: number,
): ConfigField => ({ key, label, scope, type: 'INT', defaultValue, range: { min, max } });

export const META: components['schemas']['Meta'] = {
  version: 'test',
  fields: [
    int('numTopics', 'Topics', 'SIMULATOR', 1, 1, 20),
    int('partitions', 'Partitions', 'TOPIC', 36, 1, 100),
    {
      key: 'ratePerPartition',
      label: 'Rate per Partition (records/s)',
      scope: 'PRODUCER',
      type: 'DOUBLE',
      defaultValue: 2.0,
    },
    { key: 'id', label: 'Group ID', scope: 'GROUP', type: 'STRING', defaultValue: 'kaos' },
    { key: 'numConsumers', label: 'Consumers', scope: 'GROUP', type: 'INT', defaultValue: 3 },
    int('sessionTimeoutMs', 'Session Timeout (ms)', 'GROUP', 45000, 1000, 300000),
    int('heartbeatIntervalMs', 'Heartbeat Interval (ms)', 'GROUP', 5000, 500, 60000),
    int('assignmentIntervalMs', 'Assignment Interval (ms)', 'GROUP', 1000, 0, 60000),
    { key: 'protocol', label: 'Protocol', scope: 'CONSUMER', type: 'PROTOCOL', defaultValue: 'consumer' },
    { key: 'assignor', label: 'Assignor', scope: 'CONSUMER', type: 'STRING', defaultValue: '' },
    { key: 'staticMember', label: 'Static Membership', scope: 'CONSUMER', type: 'BOOLEAN', defaultValue: false },
    { key: 'rackId', label: 'Rack ID', scope: 'CONSUMER', type: 'STRING', defaultValue: '' },
    int('pollTimeoutMs', 'Poll Timeout (ms)', 'CONSUMER', 0, 0, 60000),
    int('pollDelayMs', 'Poll Delay (ms)', 'CONSUMER', 1000, 0, 120000),
    int('maxPollIntervalMs', 'Max Poll Interval (ms)', 'CONSUMER', 300000, 1000, 600000),
  ],
  nameRule: { pattern: '[a-zA-Z0-9._-]+', maxLength: 249 },
  assignors: {
    consumer: { names: ['uniform', 'range'], customAllowed: true },
    classic: { names: ['range', 'roundrobin', 'sticky', 'cooperative-sticky'], customAllowed: false },
  },
  faults: ['freeze', 'network-isolation', 'connection-reset'],
};
