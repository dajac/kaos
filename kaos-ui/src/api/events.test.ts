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

import type { Snapshot, Status } from './client';
import { openEventStream, type EventHandlers, type EventSourceLike } from './events';

class FakeEventSource implements EventSourceLike {
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {}

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
  }

  open(): void {
    this.onopen?.(new Event('open'));
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }

  emit(type: string, data: string): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data }));
    }
  }
}

const STATUS: Status = { phase: 'RUNNING', lastError: '' };
const SNAPSHOT: Snapshot = {
  revision: 3,
  phase: 'RUNNING',
  stale: false,
  lastError: '',
  topics: [],
  producers: [],
  groups: [],
};

function setup(options: { initialBackoffMs?: number; maxBackoffMs?: number } = {}) {
  const sources: FakeEventSource[] = [];
  const handlers = {
    onStatus: vi.fn<EventHandlers['onStatus']>(),
    onSnapshot: vi.fn<EventHandlers['onSnapshot']>(),
    onConnectionChange: vi.fn<EventHandlers['onConnectionChange']>(),
  };
  const stream = openEventStream(handlers, {
    ...options,
    factory: (url) => {
      const source = new FakeEventSource(url);
      sources.push(source);
      return source;
    },
  });
  const last = () => {
    const source = sources.at(-1);
    if (source === undefined) {
      throw new Error('no event source');
    }
    return source;
  };
  return { sources, handlers, stream, last };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('openEventStream', () => {
  it('opens the events of the simulator and hands over the status and the snapshots', () => {
    const { sources, handlers, last } = setup();

    expect(sources.map((s) => s.url)).toEqual(['/api/v1/simulator/events']);
    last().open();
    last().emit('status', JSON.stringify(STATUS));
    last().emit('snapshot', JSON.stringify(SNAPSHOT));

    expect(handlers.onConnectionChange).toHaveBeenCalledWith(true);
    expect(handlers.onStatus).toHaveBeenCalledWith(STATUS);
    expect(handlers.onSnapshot).toHaveBeenCalledWith(SNAPSHOT);
  });

  it('ignores an event whose data is not JSON', () => {
    const { handlers, last } = setup();

    last().emit('snapshot', '{not json');

    expect(handlers.onSnapshot).not.toHaveBeenCalled();
  });

  it('reconnects after a failure, with a pause that doubles up to the maximum', () => {
    const { sources, handlers, last } = setup({ initialBackoffMs: 100, maxBackoffMs: 350 });

    last().fail();
    expect(sources[0]?.closed).toBe(true);
    expect(handlers.onConnectionChange).toHaveBeenLastCalledWith(false);

    const pauses: number[] = [];
    for (let attempt = 0; attempt < 4; attempt++) {
      const count = sources.length;
      let waited = 0;
      while (sources.length === count) {
        vi.advanceTimersByTime(1);
        waited++;
      }
      pauses.push(waited);
      last().fail();
    }

    expect(pauses).toEqual([100, 200, 350, 350]);
  });

  it('starts the pause over once a stream opens', () => {
    const { sources, last } = setup({ initialBackoffMs: 100 });

    last().fail();
    vi.advanceTimersByTime(100);
    last().fail();
    vi.advanceTimersByTime(200);
    expect(sources).toHaveLength(3);

    last().open();
    last().fail();
    vi.advanceTimersByTime(99);
    expect(sources).toHaveLength(3);
    vi.advanceTimersByTime(1);
    expect(sources).toHaveLength(4);
  });

  it('ignores the events of a stream that failed', () => {
    const { sources, handlers } = setup();
    const first = sources[0];

    first?.fail();
    first?.emit('status', JSON.stringify(STATUS));

    expect(handlers.onStatus).not.toHaveBeenCalled();
  });

  it('closes for good, a pending reconnect included', () => {
    const { sources, stream, last } = setup({ initialBackoffMs: 100 });

    last().open();
    stream.close();
    expect(sources[0]?.closed).toBe(true);

    const reopened = setup({ initialBackoffMs: 100 });
    reopened.last().fail();
    reopened.stream.close();
    vi.advanceTimersByTime(1000);
    expect(reopened.sources).toHaveLength(1);
  });
});
