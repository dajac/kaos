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

// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import indexHtml from '../../index.html?raw';
import { applyTheme, followTheme, resolveTheme, themeOverride } from './theme';
import { createUiSettingsStore, UI_SETTINGS_KEY, type SettingsStorage } from './uiSettingsStore';

// The theme of the page: its resolution, the attribute on <html>, following
// the OS under `system`, the `?theme` override, and the inline script of
// index.html, which must set the same attribute before the first paint.

/** A `prefers-color-scheme: light` query whose answer the test changes. */
function fakeMedia(prefersLight: boolean) {
  const listeners = new Set<() => void>();
  const media = {
    matches: prefersLight,
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
    listeners,
    change(next: boolean) {
      media.matches = next;
      listeners.forEach((listener) => listener());
    },
  };
  vi.stubGlobal('matchMedia', () => media);
  return media;
}

function memoryStorage(initial?: string): SettingsStorage & { readonly items: Map<string, string> } {
  const items = new Map<string, string>();
  if (initial !== undefined) {
    items.set(UI_SETTINGS_KEY, initial);
  }
  return { items, getItem: (key) => items.get(key) ?? null, setItem: (key, value) => void items.set(key, value) };
}

const theme = () => document.documentElement.dataset.theme;

beforeEach(() => {
  delete document.documentElement.dataset.theme;
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('resolveTheme', () => {
  it('gives the explicit choice, and the OS preference under system', () => {
    expect(resolveTheme('light', false)).toBe('light');
    expect(resolveTheme('dark', true)).toBe('dark');
    expect(resolveTheme('system', true)).toBe('light');
    expect(resolveTheme('system', false)).toBe('dark');
  });
});

describe('applyTheme', () => {
  it('sets data-theme on the root element', () => {
    fakeMedia(true);
    applyTheme('dark');
    expect(theme()).toBe('dark');
    applyTheme('system');
    expect(theme()).toBe('light');
  });

  it('follows the OS under system, and stops when asked', () => {
    const media = fakeMedia(false);
    const stop = applyTheme('system');
    expect(theme()).toBe('dark');

    media.change(true);
    expect(theme()).toBe('light');

    stop();
    expect(media.listeners.size).toBe(0);
    media.change(false);
    expect(theme()).toBe('light');
  });

  it('does not follow the OS under an explicit choice', () => {
    const media = fakeMedia(false);
    applyTheme('light');
    expect(media.listeners.size).toBe(0);
  });
});

describe('followTheme', () => {
  it('applies the stored choice, then every change of it', () => {
    const media = fakeMedia(false);
    const store = createUiSettingsStore(memoryStorage('{"theme":"system"}'));
    followTheme(store, '');
    expect(theme()).toBe('dark');
    expect(media.listeners.size).toBe(1);

    store.getState().setTheme('light');
    expect(theme()).toBe('light');
    expect(media.listeners.size).toBe(0);
  });

  it('lets ?theme win over the stored choice without writing it', () => {
    fakeMedia(false);
    const storage = memoryStorage('{"stripHeightPreset":"S","theme":"dark"}');
    followTheme(createUiSettingsStore(storage), '?theme=light');

    expect(theme()).toBe('light');
    expect(storage.items.get(UI_SETTINGS_KEY)).toBe('{"stripHeightPreset":"S","theme":"dark"}');
  });

  it('applies a click on the stored choice under ?theme, and keeps ?theme on any other change', () => {
    fakeMedia(false);
    const store = createUiSettingsStore(memoryStorage('{"stripHeightPreset":"S","theme":"dark"}'));
    followTheme(store, '?theme=light');

    store.getState().setStripHeightPreset('L');
    store.getState().setStripHeightPreset('L');
    store.setState({});
    expect(theme()).toBe('light');

    store.getState().setTheme('dark');
    expect(theme()).toBe('dark');
  });

  it('ignores an unknown ?theme', () => {
    expect(themeOverride('?theme=sepia')).toBeUndefined();
    expect(themeOverride('?theme=system')).toBe('system');
  });
});

describe('the inline script of index.html', () => {
  const script = /<script>([\s\S]*?)<\/script>/.exec(indexHtml)?.[1] ?? '';

  /** Runs the script against a root element of its own and returns its data-theme. */
  function runScript(search: string, stored: string | null, prefersLight: boolean): string | undefined {
    const root = document.createElement('html');
    const run = new Function('location', 'localStorage', 'matchMedia', 'document', script);
    run({ search }, { getItem: () => stored }, () => ({ matches: prefersLight }), { documentElement: root });
    return root.dataset.theme;
  }

  /** What the app shows: the theme `followTheme` applies for the same state. */
  function appTheme(search: string, stored: string | null, prefersLight: boolean): string | undefined {
    fakeMedia(prefersLight);
    followTheme(createUiSettingsStore(memoryStorage(stored ?? undefined)), search)();
    return theme();
  }

  it('is in the page', () => {
    expect(script).toContain('kaos.uiSettings');
  });

  const searches = ['', '?theme=light', '?theme=dark', '?theme=system', '?theme=sepia'];
  const stored = [null, '{"theme":"light"}', '{"theme":"dark"}', '{"theme":"system"}', '{"theme":7}', 'null', '{not json'];
  for (const search of searches) {
    for (const value of stored) {
      for (const prefersLight of [false, true]) {
        it(`sets the theme of the app for search "${search}", storage ${value} and a ${prefersLight ? 'light' : 'dark'} OS`, () => {
          // Without the attribute, `:root` is the dark theme.
          expect(runScript(search, value, prefersLight) ?? 'dark').toBe(appTheme(search, value, prefersLight));
        });
      }
    }
  }
});
