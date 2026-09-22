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

// The settings of the presentation, which are not part of the simulation: the
// height of the strips (S/M/L) of the ⚙ menu. They are kept in `localStorage`
// under `kaos.uiSettings`, as in the prototype, and a missing, corrupt or
// unavailable storage gives the defaults.

export const UI_SETTINGS_KEY = 'kaos.uiSettings';

export const STRIP_HEIGHT_PRESETS = { S: 48, M: 72, L: 120 } as const;

export type StripHeightPreset = keyof typeof STRIP_HEIGHT_PRESETS;

export const DEFAULT_STRIP_HEIGHT_PRESET: StripHeightPreset = 'M';

export interface UiSettingsState {
  readonly stripHeightPreset: StripHeightPreset;
  setStripHeightPreset(preset: StripHeightPreset): void;
}

/** The part of `Storage` that the settings use. */
export type SettingsStorage = Pick<Storage, 'getItem' | 'setItem'>;

function isPreset(value: unknown): value is StripHeightPreset {
  return typeof value === 'string' && Object.hasOwn(STRIP_HEIGHT_PRESETS, value);
}

function defaultStorage(): SettingsStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

function read(storage: SettingsStorage | undefined): StripHeightPreset {
  try {
    const stored = storage?.getItem(UI_SETTINGS_KEY);
    if (stored) {
      const parsed: unknown = JSON.parse(stored);
      if (typeof parsed === 'object' && parsed !== null && 'stripHeightPreset' in parsed) {
        const { stripHeightPreset } = parsed;
        if (isPreset(stripHeightPreset)) {
          return stripHeightPreset;
        }
      }
    }
  } catch {
    // Unavailable or corrupt: the defaults.
  }
  return DEFAULT_STRIP_HEIGHT_PRESET;
}

export function createUiSettingsStore(storage: SettingsStorage | undefined = defaultStorage()): StoreApi<UiSettingsState> {
  return createStore<UiSettingsState>()((set) => ({
    stripHeightPreset: read(storage),

    setStripHeightPreset(preset) {
      const stripHeightPreset = isPreset(preset) ? preset : DEFAULT_STRIP_HEIGHT_PRESET;
      set({ stripHeightPreset });
      try {
        storage?.setItem(UI_SETTINGS_KEY, JSON.stringify({ stripHeightPreset }));
      } catch {
        // Best effort.
      }
    },
  }));
}

/** The UI settings of the app. */
export const uiSettingsStore = createUiSettingsStore();

export function useUiSettings<T>(selector: (state: UiSettingsState) => T): T {
  return useStore(uiSettingsStore, selector);
}
