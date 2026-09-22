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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ConsumerSnapshot, FaultId } from '../../api/client';
import type { components } from '../../api/schema';
import { consumer, group } from '../../model/snapshot.fixture';
import { simulationStore } from '../../state/simulationStore';
import { ActionMenu } from './ActionMenu';
import { ConsumerRow } from './ConsumerRow';

// The state badge of a consumer row for every status, and the "Actions ▾"
// menu for every membership, protocol and set of faults: which items it
// shows, which are enabled, and the request each one sends.

type ConsumerStatus = components['schemas']['ConsumerStatus'];
type Protocol = components['schemas']['Protocol'];

const STATUSES: readonly ConsumerStatus[] = [
  'FAILED',
  'STOPPED',
  'FROZEN',
  'ISOLATED',
  'STOPPING',
  'JOINING',
  'RECONCILING',
  'STABLE',
];
const PROTOCOLS: readonly Protocol[] = ['consumer', 'classic'];
const FAULT_SETS: readonly (readonly FaultId[])[] = [[], ['freeze'], ['network-isolation'], ['freeze', 'network-isolation']];

const TOPICS = [{ name: 'orders-1', partitions: 4 }];

let fetchMock: ReturnType<typeof vi.fn<(input: string, init?: RequestInit) => Promise<Response>>>;

beforeEach(() => {
  simulationStore.setState({ snapshot: null, status: null, connection: 'open', requestError: null });
  fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('status badge', () => {
  const expected: Record<ConsumerStatus, { text: string; cls: string; classic: boolean }> = {
    FAILED: { text: 'FAILED', cls: 'stopping', classic: true },
    STOPPED: { text: 'STOPPED', cls: 'stopped', classic: true },
    FROZEN: { text: 'FROZEN', cls: 'frozen', classic: true },
    ISOLATED: { text: 'PARTITIONED', cls: 'partitioned', classic: true },
    STOPPING: { text: 'STOPPING', cls: 'stopping', classic: false },
    JOINING: { text: 'JOINING', cls: 'joining', classic: false },
    RECONCILING: { text: 'RECONCILING', cls: 'reconciling', classic: false },
    STABLE: { text: 'STABLE', cls: 'stable', classic: false },
  };

  for (const protocol of PROTOCOLS) {
    for (const status of STATUSES) {
      const { text, cls, classic } = expected[status];
      const shown = protocol === 'consumer' || classic;

      it(`${shown ? 'shows' : 'hides'} ${status} of a ${protocol} member`, () => {
        const { container } = render(
          <ConsumerRow
            groupId="orders"
            consumer={consumer('consumer-0', { protocol, status })}
            topics={TOPICS}
            lag={group().lag}
          />,
        );

        // The panel takes the class of the status, badge or not.
        expect(container.firstElementChild?.className).toBe(`row-panel ${cls}`);
        const badge = container.querySelector('.row-state-badge');
        if (shown) {
          expect(badge?.className).toBe(`row-state-badge ${cls}`);
          expect(badge?.textContent).toBe(text);
        } else {
          expect(badge).toBeNull();
        }
      });
    }
  }

  it('gives a failed member the failure as the title of its badge', () => {
    render(
      <ConsumerRow
        groupId="orders"
        consumer={consumer('consumer-0', { protocol: 'classic', status: 'FAILED', failure: 'unknown assignor' })}
        topics={TOPICS}
        lag={group().lag}
      />,
    );

    expect(screen.getByText('FAILED').getAttribute('title')).toBe('unknown assignor');
  });
});

interface Item {
  readonly label: string;
  readonly destructive: boolean;
  readonly enabled: boolean;
  /** The request of the item: method, path under the consumer and body. */
  readonly request: readonly [string, string, unknown];
}

// The menu of the prototype (`buildActionMenuItems` of `simulator.html` on
// `initial-setup`): the items depend on the membership, the protocol and the
// faults, not on the status; the items that need a member are disabled out of
// the group.
function prototypeMenu(protocol: Protocol, subscribed: boolean, faults: readonly FaultId[]): Item[] {
  const items: Item[] = [];
  items.push(
    subscribed
      ? { label: 'Stop consumer', destructive: true, enabled: true, request: ['POST', '/actions', { type: 'leave' }] }
      : { label: 'Start consumer', destructive: false, enabled: true, request: ['POST', '/actions', { type: 'join' }] },
  );
  items.push(
    protocol === 'classic'
      ? {
          label: 'Upgrade to consumer',
          destructive: false,
          enabled: subscribed,
          request: ['POST', '/actions', { type: 'switch-protocol', protocol: 'consumer' }],
        }
      : {
          label: 'Downgrade to classic',
          destructive: false,
          enabled: subscribed,
          request: ['POST', '/actions', { type: 'switch-protocol', protocol: 'classic' }],
        },
  );
  items.push(
    faults.includes('network-isolation')
      ? {
          label: 'Heal network',
          destructive: false,
          enabled: true,
          request: ['DELETE', '/faults/network-isolation', undefined],
        }
      : {
          label: 'Partition network',
          destructive: true,
          enabled: subscribed,
          request: ['PUT', '/faults/network-isolation', undefined],
        },
  );
  items.push(
    faults.includes('freeze')
      ? { label: 'Unfreeze consumer', destructive: false, enabled: true, request: ['DELETE', '/faults/freeze', undefined] }
      : { label: 'Freeze consumer', destructive: true, enabled: subscribed, request: ['PUT', '/faults/freeze', undefined] },
  );
  return items;
}

function menuOf(snapshot: ConsumerSnapshot) {
  const { container } = render(<ActionMenu groupId="orders" consumer={snapshot} />);
  const button = screen.getByRole('button', { name: 'Actions' });
  const menu = container.querySelector<HTMLElement>('.chaos-menu');
  if (menu === null) {
    throw new Error('the menu is missing');
  }
  return { button, menu };
}

const CONSUMER_PATH = '/api/v1/simulator/groups/orders/consumers/consumer-0';

describe('actions', () => {
  for (const protocol of PROTOCOLS) {
    for (const subscribed of [true, false]) {
      for (const faults of FAULT_SETS) {
        const expected = prototypeMenu(protocol, subscribed, faults);
        const what = `a ${protocol} member ${subscribed ? 'in' : 'out of'} the group with faults [${faults.join(', ')}]`;

        it(`offers ${expected.map((item) => item.label).join(', ')} to ${what}, whatever its status`, () => {
          for (const status of STATUSES) {
            const { menu } = menuOf(consumer('consumer-0', { protocol, subscribed, faults: [...faults], status }));

            const items = within(menu).getAllByRole('button');
            expect(
              items.map((item) => ({
                label: item.textContent,
                destructive: item.classList.contains('destructive'),
                enabled: !(item as HTMLButtonElement).disabled,
              })),
            ).toEqual(expected.map(({ label, destructive, enabled }) => ({ label, destructive, enabled })));
            expect([...menu.querySelectorAll('.chaos-group-label')].map((label) => label.textContent)).toEqual([
              'Membership',
              'Fault Injection',
            ]);
            cleanup();
          }
        });

        it(`sends the request of each enabled item of ${what}, and nothing for a disabled one`, async () => {
          const user = userEvent.setup();
          for (const item of expected) {
            fetchMock.mockClear();
            const { button, menu } = menuOf(consumer('consumer-0', { protocol, subscribed, faults: [...faults] }));

            await user.click(button);
            expect(button.getAttribute('aria-expanded')).toBe('true');
            await user.click(within(menu).getByRole('button', { name: item.label }));

            if (item.enabled) {
              const [method, path, body] = item.request;
              expect(fetchMock).toHaveBeenCalledTimes(1);
              const [url, init] = fetchMock.mock.calls[0]!;
              expect(url).toBe(CONSUMER_PATH + path);
              expect(init?.method).toBe(method);
              expect(init?.body === undefined ? undefined : JSON.parse(String(init.body))).toEqual(body);
              expect(button.getAttribute('aria-expanded')).toBe('false');
            } else {
              expect(fetchMock).not.toHaveBeenCalled();
            }
            cleanup();
          }
        });
      }
    }
  }

  it('shows the failure of a request in the store', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'not running' }), {
        status: 409,
      }),
    );
    const user = userEvent.setup();
    const { button, menu } = menuOf(consumer('consumer-0'));

    await user.click(button);
    await user.click(within(menu).getByRole('button', { name: 'Freeze consumer' }));

    await vi.waitFor(() =>
      expect(simulationStore.getState().requestError).toBe('Injecting freeze on consumer-0 failed: not running'),
    );
  });
});
