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

import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreApi } from 'zustand';

import { consumer, group, snapshot } from '../../model/snapshot.fixture';
import { configStore } from '../../state/configStore';
import { simulationStore } from '../../state/simulationStore';
import { ActionMenu } from './ActionMenu';
import { ConsumerRow } from './ConsumerRow';
import { GroupHeader } from './GroupHeader';
import { RowsView } from './RowsView';

// A render on the server reads the initial state of a store; the components
// read its current state here instead.
vi.mock('zustand', () => ({
  useStore: <S, T>(api: StoreApi<S>, selector: (state: S) => T) => selector(api.getState()),
}));

const TOPICS = [
  { name: 'orders-1', partitions: 4 },
  { name: 'orders-2', partitions: 4 },
];

beforeEach(() => {
  simulationStore.setState({ snapshot: null, status: null, connection: 'open', requestError: null });
  configStore.setState({ phase: 'IDLE' });
});

describe('ConsumerRow', () => {
  it('renders the strip, the gauges and the tooltips of the cells', () => {
    const g = group();
    const html = renderToStaticMarkup(
      <ConsumerRow groupId="orders" consumer={g.consumers[0]!} topics={TOPICS} lag={g.lag} />,
    );

    expect(html).toContain('<div class="row-panel stable">');
    expect(html).toContain('<span class="row-state-badge stable">STABLE</span>');
    expect(html).toContain('<div class="pcell assigned" data-tip="orders-1 / p0 · assigned"></div>');
    expect(html).toContain(
      '<div class="pcell pending" style="--fill:15%" data-tip="orders-1 / p1 · pending · lag 12"></div>',
    );
    expect(html).toContain('<div class="pcell revoking" data-tip="orders-2 / p3 · revoking"></div>');
    expect(html.match(/class="pcell empty"/g)).toHaveLength(5);
    expect(html).toContain('<span class="row-axis-topic-label" title="orders-1">orders-1</span>');
    expect(html).toContain('<span class="row-axis-tick" style="left:12.50%">0</span>');
    expect(html).toContain('<span class="row-header-meta-label">epoch</span><span class="row-header-meta-value">5</span>');
    expect(html).toContain('<span class="row-header-meta-value dim">—</span>');
  });

  it('hides the badge of a healthy classic member and shows its generation', () => {
    const g = group();
    const html = renderToStaticMarkup(
      <ConsumerRow groupId="orders" consumer={g.consumers[1]!} topics={TOPICS} lag={g.lag} />,
    );

    expect(html).not.toContain('row-state-badge');
    expect(html).toContain('<span class="row-header-meta-label">generation</span><span class="row-header-meta-value">4</span>');
  });

  it('shows a stopped consumer out of the group', () => {
    const html = renderToStaticMarkup(
      <ConsumerRow
        groupId="orders"
        consumer={consumer('consumer-2', { subscribed: false, status: 'STOPPED', memberId: '' })}
        topics={TOPICS}
        lag={group().lag}
      />,
    );

    expect(html).toContain('<div class="row-panel stopped">');
    expect(html).toContain('<span class="row-state-badge stopped">STOPPED</span>');
    expect(html.match(/class="pcell empty"/g)).toHaveLength(8);
  });
});

describe('ActionMenu', () => {
  const labels = (html: string) => [...html.matchAll(/<button type="button" class="chaos-item[^"]*"( disabled="")?>([^<]+)</g)]
    .map((m) => `${m[2]}${m[1] === undefined ? '' : ' (disabled)'}`);

  it('offers stop, downgrade, partition and freeze to a consumer in the group', () => {
    const html = renderToStaticMarkup(<ActionMenu groupId="orders" consumer={consumer('consumer-0')} />);

    expect(labels(html)).toEqual(['Stop consumer', 'Downgrade to classic', 'Partition network', 'Freeze consumer']);
    expect(html).toContain('<span class="dd-value">Actions</span>');
  });

  it('offers the way back from each fault, and start to a stopped consumer', () => {
    const faulty = consumer('consumer-0', { protocol: 'classic', faults: ['freeze', 'network-isolation'] });
    expect(labels(renderToStaticMarkup(<ActionMenu groupId="orders" consumer={faulty} />))).toEqual([
      'Stop consumer',
      'Upgrade to consumer',
      'Heal network',
      'Unfreeze consumer',
    ]);

    const stopped = consumer('consumer-0', { subscribed: false });
    expect(labels(renderToStaticMarkup(<ActionMenu groupId="orders" consumer={stopped} />))).toEqual([
      'Start consumer',
      'Downgrade to classic (disabled)',
      'Partition network (disabled)',
      'Freeze consumer (disabled)',
    ]);
  });
});

describe('GroupHeader', () => {
  it('shows STARTING until the group is reported', () => {
    const html = renderToStaticMarkup(<GroupHeader groupId={undefined} />);

    expect(html).toContain('<span id="status-state" class="status-badge s-idle">STARTING</span>');
    expect(html).toContain('<span id="status-epoch" class="status-value">—</span>');
  });

  it('shows the epochs of a consumer group and the generation of a classic one', () => {
    simulationStore.getState().ingestSnapshot(snapshot());
    const consumerGroup = renderToStaticMarkup(<GroupHeader groupId="orders" />);
    expect(consumerGroup).toContain('<span id="status-type" class="status-badge s-type">CONSUMER</span>');
    expect(consumerGroup).toContain('<span id="status-state" class="status-badge s-stable">STABLE</span>');
    expect(consumerGroup).toContain('<span id="status-target-epoch" class="status-value">7</span>');
    expect(consumerGroup).not.toContain('generation');

    simulationStore
      .getState()
      .ingestSnapshot(snapshot({ groups: [group({ type: 'CLASSIC', state: 'PREPARING_REBALANCE', generation: 3 })] }));
    const classicGroup = renderToStaticMarkup(<GroupHeader groupId="orders" />);
    expect(classicGroup).toContain('class="status-badge s-reconciling">PREPARING REBALANCE</span>');
    expect(classicGroup).toContain('<span id="status-generation" class="status-value">3</span>');
    expect(classicGroup).not.toContain('status-item-epoch');
  });
});

describe('RowsView', () => {
  it('shows the empty state while no simulation runs', () => {
    expect(renderToStaticMarkup(<RowsView />)).toContain(
      '<div class="empty-state-text">Configure and start a simulation</div>',
    );
  });

  it('shows the rows of the group while it runs, and the banner when the stream is down', () => {
    configStore.setState({ phase: 'RUNNING' });
    simulationStore.getState().ingestSnapshot(snapshot());
    simulationStore.setState({ connection: 'disconnected' });

    const html = renderToStaticMarkup(<RowsView />);

    expect(html).toContain('<div class="rows-grid" id="rows-grid" style="--strip-height:72px">');
    expect(html.match(/class="row-panel /g)).toHaveLength(2);
    expect(html).toContain('Disconnected from the server. Reconnecting');
  });
});
