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

import type { StoreApi } from 'zustand/vanilla';

import { isThemeChoice, type ThemeChoice, type UiSettingsState } from './uiSettingsStore';

// The theme of the page: `tokens.css` holds the dark theme on `:root` and the
// light one on `:root[data-theme="light"]`. The inline script of `index.html`
// repeats `resolveTheme` and `themeOverride` to set the attribute before the
// first paint; `theme.test.ts` keeps the two in agreement.

export type Theme = 'light' | 'dark';

const PREFERS_LIGHT = '(prefers-color-scheme: light)';

export function resolveTheme(choice: ThemeChoice, prefersLight: boolean): Theme {
  if (choice === 'system') {
    return prefersLight ? 'light' : 'dark';
  }
  return choice;
}

/** The choice of the `?theme=` query parameter, which wins over the stored one for one page load. */
export function themeOverride(search: string): ThemeChoice | undefined {
  const theme = new URLSearchParams(search).get('theme');
  return isThemeChoice(theme) ? theme : undefined;
}

/**
 * Sets `data-theme` on the root element for the choice; under `system`, follows
 * the changes of the operating system until the returned function is called.
 */
export function applyTheme(choice: ThemeChoice): () => void {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia(PREFERS_LIGHT) : undefined;
  const apply = () => {
    document.documentElement.dataset.theme = resolveTheme(choice, media?.matches ?? false);
  };
  apply();
  if (choice !== 'system' || media === undefined) {
    return () => {};
  }
  media.addEventListener('change', apply);
  return () => media.removeEventListener('change', apply);
}

/**
 * Applies the theme of the settings, or of the `?theme=` override, and again
 * at every change of the setting, until the returned function is called.
 */
export function followTheme(store: StoreApi<UiSettingsState>, search: string): () => void {
  let stop = applyTheme(themeOverride(search) ?? store.getState().theme);
  const unsubscribe = store.subscribe((state, previous) => {
    if (state.theme !== previous.theme) {
      stop();
      stop = applyTheme(state.theme);
    }
  });
  return () => {
    unsubscribe();
    stop();
  };
}
