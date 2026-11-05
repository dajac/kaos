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
// height of the strips (S/M/L) and the theme of the ⚙ menu. They are kept in
// `localStorage` under `kaos.uiSettings`, as in the prototype; each field is
// read on its own, and a missing, corrupt or unavailable value gives its
// default.

export const UI_SETTINGS_KEY = 'kaos.uiSettings';

export const STRIP_HEIGHT_PRESETS = { S: 48, M: 72, L: 120 } as const;

export type StripHeightPreset = keyof typeof STRIP_HEIGHT_PRESETS;

export const DEFAULT_STRIP_HEIGHT_PRESET: StripHeightPreset = 'M';

export const THEME_CHOICES = ['system', 'light', 'dark'] as const;

/** The theme the user picks: `system` follows the operating system. */
export type ThemeChoice = (typeof THEME_CHOICES)[number];

export const DEFAULT_THEME: ThemeChoice = 'dark';

export interface UiSettingsState {
  readonly stripHeightPreset: StripHeightPreset;
  readonly theme: ThemeChoice;
  setStripHeightPreset(preset: StripHeightPreset): void;
  setTheme(theme: ThemeChoice): void;
}

/** The part of `Storage` that the settings use. */
export type SettingsStorage = Pick<Storage, 'getItem' | 'setItem'>;

type Settings = Pick<UiSettingsState, 'stripHeightPreset' | 'theme'>;

function isPreset(value: unknown): value is StripHeightPreset {
  return typeof value === 'string' && Object.hasOwn(STRIP_HEIGHT_PRESETS, value);
}

export function isThemeChoice(value: unknown): value is ThemeChoice {
  return typeof value === 'string' && (THEME_CHOICES as readonly string[]).includes(value);
}

function defaultStorage(): SettingsStorage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

function read(storage: SettingsStorage | undefined): Settings {
  let parsed: Partial<Record<keyof Settings, unknown>> = {};
  try {
    const stored = storage?.getItem(UI_SETTINGS_KEY);
    const value: unknown = stored ? JSON.parse(stored) : null;
    if (typeof value === 'object' && value !== null) {
      parsed = value;
    }
  } catch {
    // Unavailable or corrupt: the defaults.
  }
  return {
    stripHeightPreset: isPreset(parsed.stripHeightPreset) ? parsed.stripHeightPreset : DEFAULT_STRIP_HEIGHT_PRESET,
    theme: isThemeChoice(parsed.theme) ? parsed.theme : DEFAULT_THEME,
  };
}

export function createUiSettingsStore(storage: SettingsStorage | undefined = defaultStorage()): StoreApi<UiSettingsState> {
  return createStore<UiSettingsState>()((set, get) => {
    // Writes both fields, so that a setter keeps the other one.
    function update(change: Partial<Settings>) {
      set(change);
      const { stripHeightPreset, theme } = get();
      try {
        storage?.setItem(UI_SETTINGS_KEY, JSON.stringify({ stripHeightPreset, theme }));
      } catch {
        // Best effort.
      }
    }

    return {
      ...read(storage),

      setStripHeightPreset(preset) {
        const stripHeightPreset = isPreset(preset) ? preset : DEFAULT_STRIP_HEIGHT_PRESET;
        // A click on the current height changes nothing; `followTheme` reads a
        // change that keeps both fields as a click on the current theme.
        if (stripHeightPreset !== get().stripHeightPreset) {
          update({ stripHeightPreset });
        }
      },

      setTheme(theme) {
        update({ theme: isThemeChoice(theme) ? theme : DEFAULT_THEME });
      },
    };
  });
}

/** The UI settings of the app. */
export const uiSettingsStore = createUiSettingsStore();

export function useUiSettings<T>(selector: (state: UiSettingsState) => T): T {
  return useStore(uiSettingsStore, selector);
}
