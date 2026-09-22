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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, type ApiClient, type Simulator, type Status } from '../api/client';
import {
  addOverride,
  buildStartRequest,
  initialConfig,
  removeConsumer,
  setGroupId,
} from '../model/configModel';
import { META } from '../model/meta.fixture';
import { groupFieldKey, overrideFieldKey } from '../model/violations';
import { createConfigStore } from './configStore';

const IDLE: Simulator = { phase: 'IDLE', spec: { topics: [], producers: [], groups: [] }, lastError: '', warnings: [] };

function status(phase: Status['phase'], lastError = ''): Status {
  return { phase, lastError, warnings: [] };
}

// A running simulation with the group `orders`, whose consumer-1 overrides its poll delay.
function running(): Simulator {
  const settings = { protocol: 'consumer', assignor: '', staticMember: false, rackId: '' } as const;
  const numbers = { pollTimeoutMs: 0, pollDelayMs: 1000, maxPollIntervalMs: 300000 };
  return {
    phase: 'RUNNING',
    lastError: '',
    warnings: [],
    spec: {
      topics: [{ name: 'orders-1', partitions: 36 }],
      producers: [{ id: 'producer-0', topics: ['orders-1'], ratePerPartition: 2 }],
      groups: [
        {
          id: 'orders',
          subscribedTopics: ['orders-1'],
          sessionTimeoutMs: 45000,
          heartbeatIntervalMs: 5000,
          assignmentIntervalMs: 1000,
          consumerDefaults: { ...settings, ...numbers },
          consumers: [0, 1, 2, 3].map((i) => ({
            id: `consumer-${i}`,
            overrides: i === 1 ? { pollDelayMs: 2500 } : {},
            effective: { ...settings, ...numbers, ...(i === 1 ? { pollDelayMs: 2500 } : {}) },
          })),
        },
      ],
    },
  };
}

function fakeClient(simulator: Simulator = IDLE) {
  return {
    getMeta: vi.fn<ApiClient['getMeta']>().mockResolvedValue(META),
    getSimulator: vi.fn<ApiClient['getSimulator']>().mockResolvedValue(simulator),
    startSimulator: vi.fn<ApiClient['startSimulator']>().mockResolvedValue(status('STARTING')),
    stopSimulator: vi.fn<ApiClient['stopSimulator']>().mockResolvedValue(status('STOPPING')),
    setAutopilot: vi.fn<ApiClient['setAutopilot']>(),
    injectFault: vi.fn<ApiClient['injectFault']>(),
    clearFault: vi.fn<ApiClient['clearFault']>(),
    runAction: vi.fn<ApiClient['runAction']>(),
  };
}

async function loaded(client = fakeClient()) {
  const store = createConfigStore({ client });
  await store.getState().load();
  return store;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('load', () => {
  it('starts from the defaults of /meta while the simulator is idle', async () => {
    const store = await loaded();

    expect(store.getState().meta).toEqual(META);
    expect(store.getState().config).toEqual(initialConfig(META));
    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().loadError).toBeNull();
  });

  it('rehydrates the panel from GET /simulator while a simulation runs, overrides included', async () => {
    const store = await loaded(fakeClient(running()));

    const group = store.getState().config?.groups[0];
    expect(store.getState().phase).toBe('RUNNING');
    expect(group?.id).toBe('orders');
    expect(group?.consumers.map((c) => c.overrides)).toEqual([{}, { pollDelayMs: 2500 }, {}, {}]);
  });

  it('follows a start in progress and rehydrates once it runs', async () => {
    const client = fakeClient({ ...IDLE, phase: 'STARTING' });
    const store = await loaded(client);
    expect(store.getState().phase).toBe('STARTING');
    expect(store.getState().config).toEqual(initialConfig(META));

    client.getSimulator.mockResolvedValue(running());
    store.getState().applyStatus(status('RUNNING'));
    await vi.runAllTimersAsync();

    expect(store.getState().phase).toBe('RUNNING');
    expect(store.getState().config?.groups[0]?.id).toBe('orders');
  });

  it('keeps the phase of a status that came before the answer of GET /simulator', async () => {
    const client = fakeClient({ ...IDLE, phase: 'STARTING' });
    client.getSimulator.mockResolvedValueOnce({ ...IDLE, phase: 'STARTING' }).mockResolvedValue(running());
    const store = createConfigStore({ client });
    store.getState().applyStatus(status('RUNNING'));

    await store.getState().load();
    await vi.runAllTimersAsync();

    expect(store.getState().phase).toBe('RUNNING');
    expect(store.getState().config?.groups[0]?.id).toBe('orders');
  });

  it('reports a failure to load', async () => {
    const client = fakeClient();
    client.getMeta.mockRejectedValue(new Error('connection refused'));

    const store = await loaded(client);

    expect(store.getState().config).toBeNull();
    expect(store.getState().loadError).toBe('Could not load the simulator: connection refused');
  });
});

describe('editGroup', () => {
  it('replaces the group and clears the error of the edited field only', async () => {
    const store = await loaded();
    store.setState({ fieldErrors: { [groupFieldKey(0, 'id')]: 'bad', [groupFieldKey(0, 'partitions')]: 'bad' } });

    store.getState().editGroup(0, (g) => setGroupId(g, 'orders'), groupFieldKey(0, 'id'));

    expect(store.getState().config?.groups[0]?.id).toBe('orders');
    expect(store.getState().fieldErrors).toEqual({ [groupFieldKey(0, 'partitions')]: 'bad' });
  });

  it('is ignored while the panel is locked, but a consumer can still be expanded', async () => {
    const store = await loaded(fakeClient(running()));
    const before = store.getState().config;

    store.getState().editGroup(0, (g) => setGroupId(g, 'changed'));
    expect(store.getState().config).toBe(before);

    store.getState().toggleConsumer(0, 1);
    expect(store.getState().config?.groups[0]?.consumers[1]?.expanded).toBe(true);
  });
});

describe('start', () => {
  it('posts the request of the form, locks the panel and follows the status until it runs', async () => {
    const client = fakeClient();
    const store = await loaded(client);

    await store.getState().start();

    expect(client.startSimulator).toHaveBeenCalledWith(buildStartRequest(META, initialConfig(META)));
    expect(store.getState().phase).toBe('STARTING');

    store.getState().applyStatus(status('STARTING'));
    expect(store.getState().phase).toBe('STARTING');

    store.getState().applyStatus(status('RUNNING'));
    await vi.runAllTimersAsync();
    expect(store.getState().phase).toBe('RUNNING');
    // The form that was started stays as it is.
    expect(store.getState().config).toEqual(initialConfig(META));
    expect(client.getSimulator).toHaveBeenCalledTimes(1);
  });

  it('keeps the phase that a status gave while the start request was in flight', async () => {
    const client = fakeClient();
    const store = await loaded(client);
    client.startSimulator.mockImplementation(async () => {
      store.getState().applyStatus(status('RUNNING'));
      return status('STARTING');
    });

    await store.getState().start();

    expect(store.getState().phase).toBe('RUNNING');
  });

  it('shows the violations of a 400 on their fields, and the others in the banner', async () => {
    const client = fakeClient();
    const store = await loaded(client);
    store.getState().editGroup(0, (g, meta) => addOverride(meta, g, 2, 'pollDelayMs'));
    client.startSimulator.mockRejectedValue(
      new ApiError(400, 'The spec is invalid', {
        type: 'about:blank',
        title: 'Bad Request',
        status: 400,
        detail: 'The spec is invalid',
        violations: [
          { field: 'groups[0].consumers[2].overrides.pollDelayMs', message: 'must be between 0 and 120000' },
          { field: 'producers[0].ratePerPartition', message: 'must be positive' },
        ],
      }),
    );

    await store.getState().start();

    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().fieldErrors).toEqual({
      [overrideFieldKey(0, 2, 'pollDelayMs')]: 'must be between 0 and 120000',
    });
    expect(store.getState().banner).toBe('The configuration is invalid.');
    expect(store.getState().bannerDetails).toEqual(['producers[0].ratePerPartition: must be positive']);
  });

  it('shows another failure in the banner', async () => {
    const client = fakeClient();
    const store = await loaded(client);
    client.startSimulator.mockRejectedValue(new ApiError(409, 'The simulator is already running'));

    await store.getState().start();

    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().fieldErrors).toEqual({});
    expect(store.getState().banner).toBe('Failed to start: The simulator is already running');
  });

  it('shows the lastError of a start that fails on the server', async () => {
    const client = fakeClient();
    const store = await loaded(client);
    await store.getState().start();

    store.getState().applyStatus(status('IDLE', 'Topic orders-1 already exists'));

    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().banner).toBe('The simulation failed to start.');
    expect(store.getState().bannerDetails).toEqual(['Topic orders-1 already exists']);

    store.getState().dismissBanner();
    expect(store.getState().banner).toBeNull();
  });

  it('refuses a group without consumers', async () => {
    const client = fakeClient();
    const store = await loaded(client);
    for (const key of [0, 1, 2, 3]) {
      store.getState().editGroup(0, (g) => removeConsumer(g, key));
    }

    await store.getState().start();

    expect(client.startSimulator).not.toHaveBeenCalled();
    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().fieldErrors).toEqual({
      [groupFieldKey(0, 'consumers')]: 'Add at least one consumer before starting.',
    });
  });
});

describe('stop', () => {
  it('stops and follows the status until the simulator is idle', async () => {
    const client = fakeClient(running());
    const store = await loaded(client);

    await store.getState().stop();
    expect(client.stopSimulator).toHaveBeenCalledOnce();
    expect(store.getState().phase).toBe('STOPPING');

    store.getState().applyStatus(status('IDLE'));
    expect(store.getState().phase).toBe('IDLE');
    expect(store.getState().banner).toBeNull();
    expect(store.getState().config?.groups[0]?.id).toBe('orders');
  });

  it('is idle at once when there was nothing to stop', async () => {
    const client = fakeClient(running());
    client.stopSimulator.mockResolvedValue(null);
    const store = await loaded(client);

    await store.getState().stop();

    expect(store.getState().phase).toBe('IDLE');
  });

  it('keeps running and shows the banner when the stop fails', async () => {
    const client = fakeClient(running());
    client.stopSimulator.mockRejectedValue(new TypeError('Failed to fetch'));
    const store = await loaded(client);

    await store.getState().stop();

    expect(store.getState().phase).toBe('RUNNING');
    expect(store.getState().banner).toBe('Failed to stop: Failed to fetch');
  });
});
