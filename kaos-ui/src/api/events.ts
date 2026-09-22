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

import type { Snapshot, Status } from './client';

// The event stream of the simulation, `GET /api/v1/simulator/events`. The
// server sends the current `status` and `snapshot` on connect, then each
// change. When the stream fails, it is closed and opened again after a pause
// that doubles from `initialBackoffMs` up to `maxBackoffMs`, and that starts
// over once a stream opens. The native reconnect of `EventSource` is not
// relied on: it gives up for good on some failures, such as a 502 of a proxy.

/** The part of `EventSource` that the stream uses, so that tests can fake it. */
export interface EventSourceLike {
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

export interface EventHandlers {
  onStatus(status: Status): void;
  onSnapshot(snapshot: Snapshot): void;
  /** Called with `true` when a stream opens and with `false` when it fails. */
  onConnectionChange(connected: boolean): void;
}

export interface EventStreamOptions {
  readonly url?: string;
  readonly factory?: EventSourceFactory;
  readonly initialBackoffMs?: number;
  readonly maxBackoffMs?: number;
}

export interface EventStream {
  /** Closes the stream for good. */
  close(): void;
}

export const DEFAULT_EVENTS_URL = '/api/v1/simulator/events';
export const INITIAL_BACKOFF_MS = 500;
export const MAX_BACKOFF_MS = 10_000;

/** Opens the event stream and keeps it open until `close()`. */
export function openEventStream(handlers: EventHandlers, options: EventStreamOptions = {}): EventStream {
  const url = options.url ?? DEFAULT_EVENTS_URL;
  const factory = options.factory ?? ((u: string) => new EventSource(u));
  const initialBackoffMs = options.initialBackoffMs ?? INITIAL_BACKOFF_MS;
  const maxBackoffMs = options.maxBackoffMs ?? MAX_BACKOFF_MS;

  let source: EventSourceLike | null = null;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let backoffMs = initialBackoffMs;
  let closed = false;

  function parse<T>(event: MessageEvent<string>): T | undefined {
    try {
      return JSON.parse(event.data) as T;
    } catch {
      return undefined;
    }
  }

  function connect(): void {
    retry = undefined;
    const current = factory(url);
    source = current;
    current.onopen = () => {
      if (source !== current) {
        return;
      }
      backoffMs = initialBackoffMs;
      handlers.onConnectionChange(true);
    };
    current.onerror = () => {
      if (source !== current) {
        return;
      }
      current.close();
      source = null;
      handlers.onConnectionChange(false);
      if (!closed) {
        retry = setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
      }
    };
    current.addEventListener('status', (event) => {
      const status = parse<Status>(event);
      if (source === current && status !== undefined) {
        handlers.onStatus(status);
      }
    });
    current.addEventListener('snapshot', (event) => {
      const snapshot = parse<Snapshot>(event);
      if (source === current && snapshot !== undefined) {
        handlers.onSnapshot(snapshot);
      }
    });
  }

  connect();

  return {
    close() {
      closed = true;
      clearTimeout(retry);
      source?.close();
      source = null;
    },
  };
}
