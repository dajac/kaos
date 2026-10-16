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
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { StartRequest } from '../../api/client';
import { initialConfig } from '../../model/configModel';
import { META } from '../../model/meta.fixture';
import { configStore } from '../../state/configStore';
import { ConfigPanel } from './ConfigPanel';

// The config panel as a user drives it: the consumer sections, their
// overrides and chips, the assignor picker and the errors of a refused start.
// The dropdown menus are always in the DOM (CSS shows the open one), so the
// queries are scoped to a section or to a dropdown.

let user: UserEvent;
const scrollIntoView = vi.fn();

beforeEach(() => {
  // jsdom has no layout: the panel scrolls to a consumer it just added.
  Element.prototype.scrollIntoView = scrollIntoView;
  configStore.setState({
    meta: META,
    config: initialConfig(META),
    phase: 'IDLE',
    loadError: null,
    fieldErrors: {},
    banner: null,
    bannerDetails: [],
  });
  user = userEvent.setup();
  render(<ConfigPanel />);
});

afterEach(() => {
  cleanup();
  scrollIntoView.mockReset();
  vi.unstubAllGlobals();
});

/** The toggle of the header of `consumer-<index>`. */
function toggleOf(index: number): HTMLElement {
  return screen.getByRole('button', { name: new RegExp(`^consumer-${index} (consumer|classic)$`) });
}

function sectionOf(index: number): HTMLElement {
  const section = toggleOf(index).closest<HTMLElement>('.config-section');
  if (section === null) {
    throw new Error(`consumer-${index} has no section`);
  }
  return section;
}

function groupSection(): HTMLElement {
  const section = screen.getByRole('button', { name: 'Group' }).closest<HTMLElement>('.config-section');
  if (section === null) {
    throw new Error('the Group section is missing');
  }
  return section;
}

/** Opens the dropdown of `container` whose button shows `current`, and picks the option named `option`. */
async function pick(container: HTMLElement, current: string, option: string | RegExp): Promise<void> {
  const button = within(container).getByRole('button', { name: `${current}▾`, expanded: false });
  await user.click(button);
  expect(button.getAttribute('aria-expanded')).toBe('true');
  const menu = button.parentElement;
  if (menu === null) {
    throw new Error('the dropdown has no root');
  }
  await user.click(within(menu).getByRole('button', { name: option }));
  expect(button.getAttribute('aria-expanded')).toBe('false');
}

function chipsOf(index: number): string[] {
  return [...sectionOf(index).querySelectorAll('.chip')].map((chip) => chip.textContent ?? '');
}

function consumerCount(): string | null {
  return document.querySelector('.consumers-header-count')?.textContent ?? null;
}

describe('consumer header', () => {
  it('has a toggle and a × that are separate buttons', () => {
    const toggle = toggleOf(1);
    const remove = screen.getByRole('button', { name: 'Remove consumer-1' });

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.contains(remove)).toBe(false);
    expect(remove.contains(toggle)).toBe(false);
    // Both sit in the header, with the prototype's class.
    expect(toggle.parentElement).toBe(remove.parentElement);
    expect(toggle.parentElement?.className).toBe('section-toggle');
  });

  it('expands and collapses with the mouse, anywhere in the header, and with the keyboard', async () => {
    const section = sectionOf(0);
    const toggle = toggleOf(0);

    await user.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(section.className).toBe('config-section expanded');

    const header = toggle.parentElement;
    if (header === null) {
      throw new Error('the toggle has no header');
    }
    await user.click(header);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(section.className).toBe('config-section');

    toggle.focus();
    await user.keyboard('{Enter}');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    await user.keyboard(' ');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('removes the consumer with the ×, also from the keyboard, without toggling it', async () => {
    expect(consumerCount()).toBe('(3)');

    await user.click(screen.getByRole('button', { name: 'Remove consumer-1' }));
    expect(consumerCount()).toBe('(2)');
    // The ids follow the positions: the last consumer is consumer-1 now.
    expect(toggleOf(1).getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('button', { name: 'Remove consumer-2' })).toBeNull();

    screen.getByRole('button', { name: 'Remove consumer-0' }).focus();
    await user.keyboard('{Enter}');
    expect(consumerCount()).toBe('(1)');
    expect(toggleOf(0).getAttribute('aria-expanded')).toBe('false');
  });
});

describe('overrides', () => {
  it('adds an override, shows it as a chip while collapsed, and removes it', async () => {
    await user.click(toggleOf(0));
    const section = sectionOf(0);
    expect(within(section).getByText('Inherits all settings from group.')).toBeDefined();

    // The menu shows the value each setting inherits.
    await pick(section, '+ Add override', 'Poll Delay (ms)1000');
    expect(within(section).queryByText('Inherits all settings from group.')).toBeNull();
    const input = within(section).getByRole('spinbutton') as HTMLInputElement;
    expect(input.value).toBe('1000');

    await user.clear(input);
    await user.type(input, '250');
    expect(configStore.getState().config?.groups[0]?.consumers[0]?.overrides).toEqual({ pollDelayMs: 250 });
    // An added override leaves the menu.
    const addMenu = within(section).getByRole('button', { name: '+ Add override▾' }).parentElement!;
    expect(within(addMenu).queryByRole('button', { name: /^Poll Delay/ })).toBeNull();

    await user.click(toggleOf(0));
    expect(chipsOf(0)).toEqual(['pollDelay=250']);

    await user.click(toggleOf(0));
    await user.click(within(section).getByTitle('Remove override — inherit from group'));
    expect(within(section).getByText('Inherits all settings from group.')).toBeDefined();
    await user.click(toggleOf(0));
    expect(chipsOf(0)).toEqual([]);
    expect(section.querySelector('.consumer-chips')).toBeNull();
  });

  it('shows the chips in menu order, whatever the order they were added in', async () => {
    await user.click(toggleOf(2));
    const section = sectionOf(2);
    await pick(section, '+ Add override', /^Rack ID/);
    await user.type(within(section).getByRole('textbox'), 'b');
    await pick(section, '+ Add override', 'Static Membershipoff');
    await pick(section, 'off', 'on');
    await user.click(toggleOf(2));

    expect(chipsOf(2)).toEqual(['static=on', 'rack=b']);
  });

  it('duplicates the overrides of the last consumer, and adds one that inherits all', async () => {
    await user.click(toggleOf(2));
    await pick(sectionOf(2), '+ Add override', /^Rack ID/);
    await user.type(within(sectionOf(2)).getByRole('textbox'), 'b');

    await user.click(screen.getByRole('button', { name: '+ Duplicate' }));
    expect(consumerCount()).toBe('(4)');
    expect(toggleOf(3).getAttribute('aria-expanded')).toBe('false');
    expect(chipsOf(3)).toEqual(['rack=b']);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(sectionOf(3));

    await user.click(screen.getByRole('button', { name: '+ Add' }));
    expect(consumerCount()).toBe('(5)');
    expect(chipsOf(4)).toEqual([]);
    expect(within(sectionOf(4)).getByText('Inherits all settings from group.')).toBeDefined();
    expect(scrollIntoView.mock.contexts[1]).toBe(sectionOf(4));
  });

  it('takes a custom assignor name on the consumer protocol', async () => {
    await user.click(toggleOf(0));
    const section = sectionOf(0);
    await pick(section, '+ Add override', 'Assignoruniform');
    expect(within(section).queryByPlaceholderText('custom assignor name')).toBeNull();

    await pick(section, 'uniform', 'custom');
    const name = within(section).getByPlaceholderText('custom assignor name') as HTMLInputElement;
    expect(name.value).toBe('');
    await user.type(name, 'my-assignor');

    expect(within(section).getByRole('button', { name: 'custom▾' })).toBeDefined();
    await user.click(toggleOf(0));
    expect(chipsOf(0)).toEqual(['assignor=my-assignor']);
  });

  it('keeps a typed custom name that is a preset', async () => {
    await user.click(toggleOf(0));
    const section = sectionOf(0);
    await pick(section, '+ Add override', 'Assignoruniform');
    await pick(section, 'uniform', 'custom');
    const name = within(section).getByPlaceholderText('custom assignor name');
    await user.type(name, 'uniform');

    expect(within(section).getByRole('button', { name: 'custom▾' })).toBeDefined();
    expect(within(section).getByPlaceholderText('custom assignor name')).toBe(name);

    await user.type(name, '-v2');
    await user.click(toggleOf(0));
    expect(chipsOf(0)).toEqual(['assignor=uniform-v2']);
  });

  it('falls back to the first preset when a protocol override refuses the assignor', async () => {
    await user.click(toggleOf(0));
    const section = sectionOf(0);
    await pick(section, '+ Add override', 'Protocolconsumer');
    await pick(section, '+ Add override', 'Assignoruniform');
    await pick(section, 'uniform', 'custom');
    await user.type(within(section).getByPlaceholderText('custom assignor name'), 'mine');

    await pick(section, 'consumer', 'classic');

    expect(toggleOf(0).textContent).toContain('classic');
    expect(within(section).getByRole('button', { name: 'range▾' })).toBeDefined();
    expect(within(section).queryByPlaceholderText('custom assignor name')).toBeNull();
    await user.click(toggleOf(0));
    expect(chipsOf(0)).toEqual(['protocol=classic', 'assignor=range']);
  });
});

describe('group assignor', () => {
  it('takes a custom name, and falls back to the first preset of a protocol that refuses it', async () => {
    const section = groupSection();
    await pick(section, 'uniform', 'custom');
    await user.type(within(section).getByPlaceholderText('custom assignor name'), 'my-assignor');
    expect(configStore.getState().config?.groups[0]?.assignor).toBe('my-assignor');

    await pick(section, 'consumer', 'classic');

    expect(configStore.getState().config?.groups[0]).toMatchObject({ protocol: 'classic', assignor: 'range' });
    expect(within(section).getByRole('button', { name: 'range▾' })).toBeDefined();
    expect(within(section).queryByPlaceholderText('custom assignor name')).toBeNull();
    // The consumers inherit the protocol.
    expect(toggleOf(0).textContent).toContain('classic');
  });

  it('keeps a typed custom name that is a preset, also once the input loses the focus', async () => {
    const section = groupSection();
    await pick(section, 'uniform', 'custom');
    const name = within(section).getByPlaceholderText('custom assignor name');
    await user.type(name, 'range');

    expect(configStore.getState().config?.groups[0]?.assignor).toBe('range');
    expect(within(section).getByRole('button', { name: 'custom▾' })).toBeDefined();
    expect(within(section).getByPlaceholderText('custom assignor name')).toBe(name);

    await user.tab();
    expect(within(section).getByRole('button', { name: 'custom▾' })).toBeDefined();
    expect(within(section).getByPlaceholderText('custom assignor name')).toBe(name);

    await user.type(name, '-sticky');
    expect(configStore.getState().config?.groups[0]?.assignor).toBe('range-sticky');
  });

  it('keeps a typed custom name that is a preset when custom is picked again', async () => {
    const section = groupSection();
    await pick(section, 'uniform', 'custom');
    await user.type(within(section).getByPlaceholderText('custom assignor name'), 'range');

    await pick(section, 'custom', 'custom');

    expect(configStore.getState().config?.groups[0]?.assignor).toBe('range');
    expect((within(section).getByPlaceholderText('custom assignor name') as HTMLInputElement).value).toBe('range');
  });

  it('leaves a typed custom name that is a preset for a picked preset or a protocol that refuses it', async () => {
    const section = groupSection();
    await pick(section, 'uniform', 'custom');
    await user.type(within(section).getByPlaceholderText('custom assignor name'), 'range');

    await pick(section, 'custom', 'uniform');

    expect(configStore.getState().config?.groups[0]?.assignor).toBe('uniform');
    expect(within(section).queryByPlaceholderText('custom assignor name')).toBeNull();

    await pick(section, 'uniform', 'custom');
    await user.type(within(section).getByPlaceholderText('custom assignor name'), 'range');
    await pick(section, 'consumer', 'classic');

    expect(configStore.getState().config?.groups[0]).toMatchObject({ protocol: 'classic', assignor: 'range' });
    expect(within(section).getByRole('button', { name: 'range▾' })).toBeDefined();
    expect(within(section).queryByPlaceholderText('custom assignor name')).toBeNull();
  });
});

describe('start', () => {
  it('shows a violation on the field it names, and clears it once the field is edited', async () => {
    const fetchMock = vi.fn(
      async (_input: string, _init?: RequestInit) =>
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            detail: 'Invalid configuration',
            violations: [{ field: 'groups[0].consumers[1].overrides.pollDelayMs', message: 'must be at most 120000' }],
          }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await user.click(toggleOf(1));
    const section = sectionOf(1);
    await pick(section, '+ Add override', 'Poll Delay (ms)1000');
    const input = within(section).getByRole('spinbutton');
    await user.clear(input);
    await user.type(input, '999999');

    await user.click(screen.getByRole('button', { name: 'Start' }));

    const alert = await within(section).findByRole('alert');
    expect(alert.textContent).toBe('must be at most 120000');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(configStore.getState().phase).toBe('IDLE');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/v1/simulator');
    expect(init?.method).toBe('POST');
    const body = JSON.parse(String(init?.body)) as StartRequest;
    expect(body.groups?.[0]?.consumers?.[1]?.overrides).toEqual({ pollDelayMs: 999999 });

    await user.type(input, '{Backspace}');
    expect(within(section).queryByRole('alert')).toBeNull();
  });

  it('keeps the form while a simulation runs, but still expands a consumer', async () => {
    configStore.setState({ phase: 'RUNNING' });
    const toggle = await screen.findByRole('button', { name: 'Stop' });
    expect(toggle).toBeDefined();

    await user.click(screen.getByRole('button', { name: 'Remove consumer-1' }));
    expect(consumerCount()).toBe('(3)');
    await user.click(toggleOf(1));
    expect(toggleOf(1).getAttribute('aria-expanded')).toBe('true');
  });

  it('shows the transient phases with an ellipsis character on a disabled button', async () => {
    configStore.setState({ phase: 'STARTING' });
    const starting = await screen.findByRole('button', { name: 'Starting…' });
    expect(starting.hasAttribute('disabled')).toBe(true);

    configStore.setState({ phase: 'STOPPING' });
    const stopping = await screen.findByRole('button', { name: 'Stopping…' });
    expect(stopping.hasAttribute('disabled')).toBe(true);
  });
});
