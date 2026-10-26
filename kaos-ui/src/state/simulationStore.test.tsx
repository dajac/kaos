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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { StoreApi } from 'zustand';

import { ApiError, type ApiClient, type Status } from '../api/client';
import type { EventSourceLike } from '../api/events';
import { consumer, group, snapshot } from '../model/snapshot.fixture';
import {
  createSimulationStore,
  selectGroup,
  selectRowsError,
  selectShownGroupId,
  simulationStore,
  useGroup,
} from './simulationStore';

// A render on the server reads the initial state of a store; the hooks read
// its current state here instead.
vi.mock('zustand', () => ({
  useStore: <S, T>(api: StoreApi<S>, selector: (state: S) => T) => selector(api.getState()),
}));

function fakeClient() {
  return {
    getMeta: vi.fn<ApiClient['getMeta']>(),
    getSimulator: vi.fn<ApiClient['getSimulator']>(),
    startSimulator: vi.fn<ApiClient['startSimulator']>(),
    stopSimulator: vi.fn<ApiClient['stopSimulator']>(),
    injectFault: vi.fn<ApiClient['injectFault']>().mockResolvedValue(undefined),
    clearFault: vi.fn<ApiClient['clearFault']>().mockResolvedValue(undefined),
    runAction: vi.fn<ApiClient['runAction']>().mockResolvedValue(null),
  };
}

const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

const running = (lastError = ''): Status => ({ phase: 'RUNNING', lastError, warnings: [] });

describe('ingest', () => {
  it('keeps the objects of the unchanged groups and consumers', () => {
    const store = createSimulationStore({ client: fakeClient() });
    store.getState().ingestSnapshot(snapshot());
    const before = selectGroup(store.getState(), 'orders');

    const next = snapshot({ revision: 2 });
    next.groups = [group({ consumers: [copy(group().consumers[0]!), consumer('consumer-1', { status: 'FROZEN' })] })];
    store.getState().ingestSnapshot(next);
    const after = selectGroup(store.getState(), 'orders');

    expect(store.getState().snapshot?.revision).toBe(2);
    expect(after).not.toBe(before);
    expect(after?.consumers[0]).toBe(before?.consumers[0]);
    expect(after?.consumers[1]?.status).toBe('FROZEN');
    expect(after?.lag).toBe(before?.lag);
  });

  it('keeps the whole group when a snapshot repeats it', () => {
    const store = createSimulationStore({ client: fakeClient() });
    store.getState().ingestSnapshot(snapshot());
    const before = selectGroup(store.getState(), 'orders');

    store.getState().ingestSnapshot(copy(snapshot({ revision: 9 })));

    expect(selectGroup(store.getState(), 'orders')).toBe(before);
  });

  it('selects a group by its id, and the first one as the shown group', () => {
    const store = createSimulationStore({ client: fakeClient() });
    expect(selectShownGroupId(store.getState())).toBeUndefined();

    store.getState().ingestSnapshot(snapshot({ groups: [group({ id: 'a' }), group({ id: 'b' })] }));

    expect(selectShownGroupId(store.getState())).toBe('a');
    expect(selectGroup(store.getState(), 'b')?.id).toBe('b');
    expect(selectGroup(store.getState(), 'c')).toBeUndefined();
  });
});

describe('connect', () => {
  it('feeds the store from the event stream, and reports its state', () => {
    const sources: (EventSourceLike & { emit(type: string, data: string): void })[] = [];
    const store = createSimulationStore({ client: fakeClient() });
    const onStatus = vi.fn();

    const close = store.getState().connect({
      onStatus,
      factory: () => {
        const listeners = new Map<string, (event: MessageEvent<string>) => void>();
        const source = {
          onopen: null as ((event: Event) => void) | null,
          onerror: null as ((event: Event) => void) | null,
          addEventListener: (type: string, listener: (event: MessageEvent<string>) => void) =>
            listeners.set(type, listener),
          close: vi.fn(),
          emit: (type: string, data: string) => listeners.get(type)?.(new MessageEvent(type, { data })),
        };
        sources.push(source);
        return source;
      },
    });
    const source = sources[0]!;

    expect(store.getState().connection).toBe('connecting');
    source.onopen?.(new Event('open'));
    source.emit('status', JSON.stringify(running()));
    source.emit('status', JSON.stringify(running()));
    source.emit('snapshot', JSON.stringify(snapshot()));

    expect(store.getState().connection).toBe('open');
    expect(store.getState().status).toEqual(running());
    expect(onStatus).toHaveBeenCalledTimes(2);
    expect(selectShownGroupId(store.getState())).toBe('orders');

    source.onerror?.(new Event('error'));
    expect(store.getState().connection).toBe('disconnected');
    close();
  });
});

describe('requests', () => {
  it('sends the actions and the faults of a consumer', async () => {
    const client = fakeClient();
    const store = createSimulationStore({ client });

    await store.getState().runAction('orders', 'consumer-0', { type: 'leave' });
    await store.getState().setFault('orders', 'consumer-0', 'freeze', true);
    await store.getState().setFault('orders', 'consumer-0', 'network-isolation', false);

    expect(client.runAction).toHaveBeenCalledWith('orders', 'consumer-0', { type: 'leave' });
    expect(client.injectFault).toHaveBeenCalledWith('orders', 'consumer-0', 'freeze');
    expect(client.clearFault).toHaveBeenCalledWith('orders', 'consumer-0', 'network-isolation');
    expect(store.getState().requestError).toBeNull();
  });

  it('shows a failed request until it is dismissed or a request succeeds', async () => {
    const client = fakeClient();
    client.runAction.mockRejectedValueOnce(new ApiError(409, 'The simulator is not running'));
    const store = createSimulationStore({ client });

    await store.getState().runAction('orders', 'consumer-0', { type: 'join' });
    expect(selectRowsError(store.getState())).toBe('join of consumer-0 failed: The simulator is not running');

    await store.getState().runAction('orders', 'consumer-0', { type: 'join' });
    expect(selectRowsError(store.getState())).toBeNull();
  });

  it('shows the lastError of a running simulation until it is dismissed', () => {
    const store = createSimulationStore({ client: fakeClient() });

    store.getState().ingestStatus(running('Switching consumer-1 failed'));
    expect(selectRowsError(store.getState())).toBe('Switching consumer-1 failed');

    store.getState().dismissErrors();
    expect(selectRowsError(store.getState())).toBeNull();

    store.getState().ingestStatus(running('Another failure'));
    expect(selectRowsError(store.getState())).toBe('Another failure');

    store.getState().ingestStatus({ phase: 'IDLE', lastError: 'Start failed', warnings: [] });
    expect(selectRowsError(store.getState())).toBeNull();
  });
});

describe('useGroup', () => {
  it('reads the group of the app store by its id', () => {
    simulationStore.getState().ingestSnapshot(snapshot());
    let seen: string | undefined;
    let missing: unknown = 'unset';
    function Probe() {
      seen = useGroup('orders')?.state;
      missing = useGroup('payments');
      return null;
    }

    renderToStaticMarkup(<Probe />);

    expect(seen).toBe('STABLE');
    expect(missing).toBeUndefined();
  });
});
