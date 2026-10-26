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

import { useStore } from 'zustand';
import { createStore, type StoreApi } from 'zustand/vanilla';

import {
  createClient,
  type ActionRequest,
  type ApiClient,
  type FaultId,
  type GroupSnapshot,
  type Snapshot,
  type Status,
} from '../api/client';
import { openEventStream, type EventStreamOptions } from '../api/events';
import { shareStructure } from '../model/share';

// The state of the running simulation, fed by the event stream: the last
// `status` and `snapshot`, and whether the stream is up. A snapshot is merged
// into the previous one with structural sharing, so that the groups,
// consumers and lag arrays that did not change keep their identity and the
// memoized rows do not render again.
//
// The store also sends the requests of the Rows view (the actions and the
// faults), and keeps the message of the last one that failed.

/** `connecting` until the first stream opens; `disconnected` from a failure until a stream opens again. */
export type Connection = 'connecting' | 'open' | 'disconnected';

export interface SimulationState {
  readonly connection: Connection;
  readonly status: Status | null;
  readonly snapshot: Snapshot | null;
  /** The message of the last request of the Rows view that failed. */
  readonly requestError: string | null;
  /** The `lastError` of the status that was dismissed, so that it shows again only when it changes. */
  readonly dismissedLastError: string | null;

  /** Opens the event stream; returns the function that closes it. */
  connect(options?: ConnectOptions): () => void;
  ingestStatus(status: Status): void;
  ingestSnapshot(snapshot: Snapshot): void;
  setConnected(connected: boolean): void;

  runAction(groupId: string, consumerId: string, action: ActionRequest): Promise<void>;
  setFault(groupId: string, consumerId: string, fault: FaultId, active: boolean): Promise<void>;
  dismissErrors(): void;
}

export interface ConnectOptions extends EventStreamOptions {
  /** Called with every `status` event, also one equal to the previous status. */
  readonly onStatus?: (status: Status) => void;
}

export interface SimulationStoreOptions {
  readonly client?: ApiClient;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function createSimulationStore(options: SimulationStoreOptions = {}): StoreApi<SimulationState> {
  const client = options.client ?? createClient();

  return createStore<SimulationState>()((set, get) => {
    async function send(what: string, request: () => Promise<unknown>): Promise<void> {
      try {
        await request();
        if (get().requestError !== null) {
          set({ requestError: null });
        }
      } catch (error) {
        set({ requestError: `${what} failed: ${messageOf(error)}` });
      }
    }

    return {
      connection: 'connecting',
      status: null,
      snapshot: null,
      requestError: null,
      dismissedLastError: null,

      connect(connectOptions = {}) {
        const { onStatus, ...streamOptions } = connectOptions;
        const stream = openEventStream(
          {
            onStatus: (status) => {
              get().ingestStatus(status);
              onStatus?.(status);
            },
            onSnapshot: (snapshot) => get().ingestSnapshot(snapshot),
            onConnectionChange: (connected) => get().setConnected(connected),
          },
          streamOptions,
        );
        return () => stream.close();
      },

      ingestStatus(status) {
        set({ status: shareStructure(get().status, status) });
      },

      ingestSnapshot(snapshot) {
        // The revision is not trusted to skip a snapshot: it starts over when
        // the server restarts. The same snapshot again, as after a reconnect,
        // keeps every object anyway.
        set({ snapshot: shareStructure(get().snapshot, snapshot) });
      },

      setConnected(connected) {
        set({ connection: connected ? 'open' : 'disconnected' });
      },

      runAction(groupId, consumerId, action) {
        return send(`${action.type} of ${consumerId}`, () => client.runAction(groupId, consumerId, action));
      },

      setFault(groupId, consumerId, fault, active) {
        return send(`${active ? 'Injecting' : 'Clearing'} ${fault} on ${consumerId}`, () =>
          active ? client.injectFault(groupId, consumerId, fault) : client.clearFault(groupId, consumerId, fault),
        );
      },

      dismissErrors() {
        set({ requestError: null, dismissedLastError: get().status?.lastError ?? null });
      },
    };
  });
}

/** The simulation store of the app. */
export const simulationStore = createSimulationStore();

/** A selector subscription to the simulation store. */
export function useSimulationStore<T>(selector: (state: SimulationState) => T): T {
  return useStore(simulationStore, selector);
}

/** The group `groupId` of the last snapshot; the same object as long as it does not change. */
export function selectGroup(state: SimulationState, groupId: string): GroupSnapshot | undefined {
  return state.snapshot?.groups.find((group) => group.id === groupId);
}

export function useGroup(groupId: string): GroupSnapshot | undefined {
  return useSimulationStore((state) => selectGroup(state, groupId));
}

/**
 * The id of the group that the UI shows. The UI is at parity with the
 * prototype, one group; the components take the group id and do not depend on
 * this choice.
 */
export function selectShownGroupId(state: SimulationState): string | undefined {
  return state.snapshot?.groups[0]?.id;
}

/** The error to show in the Rows view: a failed request, or a `lastError` while the simulation runs. */
export function selectRowsError(state: SimulationState): string | null {
  if (state.requestError !== null) {
    return state.requestError;
  }
  const lastError = state.status?.phase === 'RUNNING' ? state.status.lastError : '';
  return lastError !== '' && lastError !== state.dismissedLastError ? lastError : null;
}
