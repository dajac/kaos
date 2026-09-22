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

import { ApiError, createClient, type ApiClient, type Meta, type Status } from '../api/client';
import type { components } from '../api/schema';
import {
  buildStartRequest,
  configFromSimulator,
  startProblems,
  toggleConsumer,
  updateGroup,
  type ConfigForm,
  type GroupForm,
} from '../model/configModel';
import { groupFieldKey, mapViolations } from '../model/violations';

// The state of the config panel: `/meta`, the form (`groups[]`), the phase of
// the simulator, and the errors of the last start.
//
// Start and Stop answer 202 at once and run on the lifecycle thread of the
// server. The store follows them through the `status` events of the event
// stream, which the app hands to `applyStatus`.

type Phase = components['schemas']['Phase'];

export interface ConfigState {
  readonly meta: Meta | null;
  readonly config: ConfigForm | null;
  readonly phase: Phase;
  /** Why the panel could not load, when `/meta` or `/simulator` failed. */
  readonly loadError: string | null;
  /** The inline errors, by field key (see `model/violations.ts`). */
  readonly fieldErrors: Readonly<Record<string, string>>;
  /** The banner: an error that belongs to no field. */
  readonly banner: string | null;
  /** The details of the banner, such as the violations that name no field. */
  readonly bannerDetails: readonly string[];

  load(): Promise<void>;
  /** Follows the status of the simulator, as the event stream sends it. */
  applyStatus(status: Status): void;
  /** Replaces the group at `index` by `update(group, meta)`, and clears the error of `field`. Ignored while locked. */
  editGroup(index: number, update: (group: GroupForm, meta: Meta) => GroupForm, field?: string): void;
  /** Expands or collapses a consumer section; also while locked, as the prototype does. */
  toggleConsumer(groupIndex: number, consumerKey: number): void;
  start(): Promise<void>;
  stop(): Promise<void>;
  dismissBanner(): void;
}

export interface ConfigStoreOptions {
  readonly client?: ApiClient;
}

type Writable<T> = { -readonly [K in keyof T]: T[K] };

const NO_ERRORS: Readonly<Record<string, string>> = {};

/** Whether the panel is locked: a simulation is starting, running or stopping. */
export function isLocked(state: Pick<ConfigState, 'phase'>): boolean {
  return state.phase !== 'IDLE';
}

function isTransient(phase: Phase): boolean {
  return phase === 'STARTING' || phase === 'STOPPING';
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function createConfigStore(options: ConfigStoreOptions = {}): StoreApi<ConfigState> {
  const client = options.client ?? createClient();
  // Whether a status has been applied: it is newer than the answer of the
  // `GET /simulator` of `load()`.
  let statusSeen = false;
  // Whether the form waits for the spec of a start in progress: a reload
  // during a start shows the running spec once the phase is `RUNNING`.
  let rehydratePending = false;

  return createStore<ConfigState>()((set, get) => {
    async function rehydrate(): Promise<void> {
      rehydratePending = false;
      try {
        const simulator = await client.getSimulator();
        const { meta } = get();
        if (meta !== null && simulator.phase === 'RUNNING' && get().phase === 'RUNNING') {
          set({ config: configFromSimulator(meta, simulator) });
        }
      } catch {
        // Keep the form as it is.
      }
    }

    return {
      meta: null,
      config: null,
      phase: 'IDLE',
      loadError: null,
      fieldErrors: NO_ERRORS,
      banner: null,
      bannerDetails: [],

      async load() {
        try {
          const [meta, simulator] = await Promise.all([client.getMeta(), client.getSimulator()]);
          const phase = statusSeen ? get().phase : simulator.phase;
          set({ meta, config: configFromSimulator(meta, simulator), loadError: null, phase });
          if (simulator.phase !== 'RUNNING' && phase !== 'IDLE') {
            // A reload during a start: show the spec once it runs.
            rehydratePending = true;
            if (phase === 'RUNNING') {
              void rehydrate();
            }
          }
        } catch (error) {
          set({ loadError: `Could not load the simulator: ${messageOf(error)}` });
        }
      },

      applyStatus(status) {
        statusSeen = true;
        const previous = get().phase;
        const update: Partial<Writable<ConfigState>> = { phase: status.phase };
        if (status.phase === 'IDLE' && isTransient(previous) && status.lastError !== '') {
          // A failed start or stop is back to IDLE with its error.
          update.banner = previous === 'STARTING' ? 'The simulation failed to start.' : 'The simulation failed to stop.';
          update.bannerDetails = [status.lastError];
        }
        set(update);
        if (status.phase === 'IDLE') {
          rehydratePending = false;
        } else if (status.phase === 'RUNNING' && rehydratePending && get().meta !== null) {
          void rehydrate();
        }
      },

      editGroup(index, update, field) {
        const { meta, config, fieldErrors } = get();
        if (meta === null || config === null || isLocked(get())) {
          return;
        }
        const next: Partial<Writable<ConfigState>> = { config: updateGroup(config, index, (group) => update(group, meta)) };
        if (field !== undefined && fieldErrors[field] !== undefined) {
          const { [field]: _cleared, ...rest } = fieldErrors;
          next.fieldErrors = rest;
        }
        set(next);
      },

      toggleConsumer(groupIndex, consumerKey) {
        const { config } = get();
        if (config !== null) {
          set({ config: updateGroup(config, groupIndex, (group) => toggleConsumer(group, consumerKey)) });
        }
      },

      async start() {
        const { meta, config } = get();
        if (meta === null || config === null || isLocked(get())) {
          return;
        }
        const problems = startProblems(config);
        if (problems.length > 0) {
          const fieldErrors: Record<string, string> = {};
          for (const problem of problems) {
            fieldErrors[groupFieldKey(problem.group, 'consumers')] ??= problem.message;
          }
          set({ fieldErrors, banner: null, bannerDetails: [] });
          return;
        }
        set({ phase: 'STARTING', fieldErrors: NO_ERRORS, banner: null, bannerDetails: [] });
        try {
          // 202: the status events follow the start from here.
          await client.startSimulator(buildStartRequest(meta, config));
        } catch (error) {
          if (error instanceof ApiError && error.violations.length > 0) {
            const { fields, unmapped } = mapViolations(config, error.violations);
            set({
              phase: 'IDLE',
              fieldErrors: fields,
              banner: unmapped.length > 0 ? 'The configuration is invalid.' : null,
              bannerDetails: unmapped.map((v) => `${v.field}: ${v.message}`),
            });
          } else {
            set({ phase: 'IDLE', banner: `Failed to start: ${messageOf(error)}`, bannerDetails: [] });
          }
          return;
        }
      },

      async stop() {
        const { phase } = get();
        if (phase !== 'RUNNING') {
          return;
        }
        set({ phase: 'STOPPING', banner: null, bannerDetails: [] });
        try {
          // 202: the status events follow the stop; 204: there was nothing to stop.
          const status = await client.stopSimulator();
          if (status === null) {
            set({ phase: 'IDLE' });
          }
        } catch (error) {
          set({ phase: 'RUNNING', banner: `Failed to stop: ${messageOf(error)}`, bannerDetails: [] });
        }
      },

      dismissBanner() {
        set({ banner: null, bannerDetails: [] });
      },
    };
  });
}

/** The config store of the app. */
export const configStore = createConfigStore();

/** A selector subscription to the config store. */
export function useConfigStore<T>(selector: (state: ConfigState) => T): T {
  return useStore(configStore, selector);
}
