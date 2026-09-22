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

import {
  cellClass,
  consumerBadge,
  DASH,
  formatNumber,
  groupStateBadge,
  groupTypeLabel,
  stripCells,
  stripSignature,
  topicStates,
} from './rows';
import { group } from './snapshot.fixture';

describe('the header', () => {
  it('shows the group state with spaces, in the colour of the prototype', () => {
    expect(groupStateBadge('STABLE')).toEqual({ text: 'STABLE', cls: 's-stable' });
    expect(groupStateBadge('PREPARING_REBALANCE')).toEqual({ text: 'PREPARING REBALANCE', cls: 's-reconciling' });
    expect(groupStateBadge('COMPLETING_REBALANCE').cls).toBe('s-reconciling');
    expect(groupStateBadge('ASSIGNING').cls).toBe('s-reconciling');
    expect(groupStateBadge('RECONCILING').cls).toBe('s-reconciling');
    expect(groupStateBadge('EMPTY')).toEqual({ text: 'EMPTY', cls: 's-empty' });
    expect(groupStateBadge('UNKNOWN').cls).toBe('s-empty');
  });

  it('shows a dash for an unknown type or number', () => {
    expect(groupTypeLabel('UNKNOWN')).toBe(DASH);
    expect(groupTypeLabel('CLASSIC')).toBe('CLASSIC');
    expect(formatNumber(-1)).toBe(DASH);
    expect(formatNumber(0)).toBe('0');
  });
});

describe('consumerBadge', () => {
  it('shows the status of a consumer-protocol member', () => {
    expect(consumerBadge({ protocol: 'consumer', status: 'STABLE' })).toEqual({
      show: true,
      text: 'STABLE',
      cls: 'stable',
    });
    expect(consumerBadge({ protocol: 'consumer', status: 'ISOLATED' })).toEqual({
      show: true,
      text: 'PARTITIONED',
      cls: 'partitioned',
    });
    expect(consumerBadge({ protocol: 'consumer', status: 'FAILED' }).cls).toBe('stopping');
  });

  it('hides the badge of a healthy classic member, not that of a stopped or faulty one', () => {
    for (const status of ['STABLE', 'RECONCILING', 'JOINING', 'STOPPING'] as const) {
      const badge = consumerBadge({ protocol: 'classic', status });
      expect(badge.show).toBe(false);
      expect(badge.cls).toBe(status.toLowerCase());
    }
    for (const status of ['STOPPED', 'FROZEN', 'ISOLATED', 'FAILED'] as const) {
      expect(consumerBadge({ protocol: 'classic', status }).show).toBe(true);
    }
  });
});

describe('stripCells', () => {
  const g = group();
  const partitions = g.consumers[0]?.partitions ?? [];

  it('gives each partition its state, its gauge and its tooltip', () => {
    const cells = stripCells('orders-1', 4, topicStates(partitions, 'orders-1'), g.lag);

    expect(cells).toEqual([
      { state: 'ASSIGNED', fillPct: 0, tip: 'orders-1 / p0 · assigned' },
      { state: 'PENDING', fillPct: 15, tip: 'orders-1 / p1 · pending · lag 12' },
      { state: 'EMPTY', fillPct: 0, tip: '' },
      { state: 'EMPTY', fillPct: 0, tip: '' },
    ]);
    expect(cells.map((c) => cellClass(c.state))).toEqual([
      'pcell assigned',
      'pcell pending',
      'pcell empty',
      'pcell empty',
    ]);
  });

  it('has a signature that follows the owned cells only', () => {
    const states = topicStates(partitions, 'orders-1');
    const before = stripSignature(stripCells('orders-1', 4, states, g.lag));

    // The lag of a partition that the consumer does not own.
    const otherLag = stripSignature(stripCells('orders-1', 4, states, { 'orders-1': [0, 12, 50, 99] }));
    // The lag of a partition that it owns.
    const ownLag = stripSignature(stripCells('orders-1', 4, states, { 'orders-1': [0, 13, -1, 3] }));

    expect(otherLag).toBe(before);
    expect(ownLag).not.toBe(before);
  });
});
