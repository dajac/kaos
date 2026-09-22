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

import { describe, expect, it } from 'vitest';

import { createUiSettingsStore, UI_SETTINGS_KEY, type SettingsStorage } from './uiSettingsStore';

function memoryStorage(initial?: string): SettingsStorage & { readonly items: Map<string, string> } {
  const items = new Map<string, string>();
  if (initial !== undefined) {
    items.set(UI_SETTINGS_KEY, initial);
  }
  return {
    items,
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => void items.set(key, value),
  };
}

describe('uiSettingsStore', () => {
  it('starts on M without a stored setting', () => {
    expect(createUiSettingsStore(memoryStorage()).getState().stripHeightPreset).toBe('M');
    expect(createUiSettingsStore(undefined).getState().stripHeightPreset).toBe('M');
  });

  it('persists the strip height under kaos.uiSettings, and reads it back', () => {
    const storage = memoryStorage();
    createUiSettingsStore(storage).getState().setStripHeightPreset('L');

    expect(JSON.parse(storage.items.get(UI_SETTINGS_KEY) ?? '')).toEqual({ stripHeightPreset: 'L' });
    expect(createUiSettingsStore(storage).getState().stripHeightPreset).toBe('L');
  });

  it('reads the setting of the prototype and ignores its UI mode', () => {
    const storage = memoryStorage(JSON.stringify({ mode: 'old', stripHeightPreset: 'S' }));

    expect(createUiSettingsStore(storage).getState().stripHeightPreset).toBe('S');
  });

  it('falls back to M on a corrupt or unknown value, and on a failing storage', () => {
    expect(createUiSettingsStore(memoryStorage('{not json')).getState().stripHeightPreset).toBe('M');
    expect(createUiSettingsStore(memoryStorage('{"stripHeightPreset":"XL"}')).getState().stripHeightPreset).toBe('M');

    const failing: SettingsStorage = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    };
    const store = createUiSettingsStore(failing);
    expect(store.getState().stripHeightPreset).toBe('M');
    store.getState().setStripHeightPreset('S');
    expect(store.getState().stripHeightPreset).toBe('S');
  });
});
