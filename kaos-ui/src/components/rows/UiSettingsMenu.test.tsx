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

import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { followTheme } from '../../state/theme';
import { UI_SETTINGS_KEY, uiSettingsStore } from '../../state/uiSettingsStore';
import { UiSettingsMenu } from './UiSettingsMenu';

// The Theme row of the ⚙ menu: it shows the stored choice, and a click
// switches the page at once and persists the choice.

let stopTheme: () => void = () => {};

beforeEach(() => {
  localStorage.clear();
  uiSettingsStore.setState({ stripHeightPreset: 'M', theme: 'dark' });
  stopTheme = followTheme(uiSettingsStore, '');
});

afterEach(() => {
  stopTheme();
  cleanup();
});

describe('UiSettingsMenu', () => {
  it('shows the stored theme, and switches and persists the clicked one', async () => {
    const user = userEvent.setup();
    render(<UiSettingsMenu />);
    await user.click(screen.getByRole('button', { name: 'UI settings' }));

    const themes = within(screen.getByRole('radiogroup', { name: 'Theme' }));
    expect(themes.getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['System', 'Light', 'Dark']);
    expect(themes.getByRole('radio', { name: 'Dark' }).getAttribute('aria-checked')).toBe('true');
    expect(document.documentElement.dataset.theme).toBe('dark');

    await user.click(themes.getByRole('radio', { name: 'Light' }));

    expect(themes.getByRole('radio', { name: 'Light' }).getAttribute('aria-checked')).toBe('true');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(JSON.parse(localStorage.getItem(UI_SETTINGS_KEY) ?? '')).toEqual({ stripHeightPreset: 'M', theme: 'light' });
  });
});
