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

import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { GroupSnapshot, Snapshot } from '../../api/client';
import { consumer } from '../../model/snapshot.fixture';
import { configStore } from '../../state/configStore';
import { simulationStore } from '../../state/simulationStore';
import type { RenderedComponent } from '../../test/renderTracker';
import { RowsView } from './RowsView';

// What renders again when a snapshot arrives, with 3 topics of 100 partitions
// and 12 consumers: only the row and the strips of what changed. The tracker
// records the components that rendered in each commit, as the profiler of
// React DevTools does.

const tracker = await vi.hoisted(async () => {
  const { installRenderTracker } = await import('../../test/renderTracker');
  return installRenderTracker();
});

const TOPICS = ['perf-1', 'perf-2', 'perf-3'];
const PARTITIONS = 100;
const CONSUMERS = 12;

/** The consumer that owns a partition: they are dealt out in turn. */
function ownerOf(topicIndex: number, partition: number): number {
  return (topicIndex * PARTITIONS + partition) % CONSUMERS;
}

function baseSnapshot(): Snapshot {
  const consumers = Array.from({ length: CONSUMERS }, (_, index) =>
    consumer(`consumer-${index}`, {
      partitions: TOPICS.flatMap((topic, topicIndex) =>
        Array.from({ length: PARTITIONS }, (_, partition) => partition)
          .filter((partition) => ownerOf(topicIndex, partition) === index)
          .map((partition) => ({ topic, partition, state: 'ASSIGNED' as const })),
      ),
    }),
  );
  const group: GroupSnapshot = {
    id: 'perf',
    type: 'CONSUMER',
    state: 'STABLE',
    epoch: 3,
    targetAssignmentEpoch: 3,
    subscribedTopics: TOPICS,
    autopilot: { enabled: false, minIntervalMs: 10000, maxIntervalMs: 30000 },
    lag: Object.fromEntries(
      TOPICS.map((topic, topicIndex) => [
        topic,
        Array.from({ length: PARTITIONS }, (_, partition) => (topicIndex + partition) % 4),
      ]),
    ),
    consumers,
  };
  return {
    revision: 1,
    phase: 'RUNNING',
    stale: false,
    lastError: '',
    topics: TOPICS.map((name) => ({ name, partitions: PARTITIONS })),
    producers: [{ id: 'producer-0', topics: TOPICS, state: 'RUNNING', ratePerPartition: 2 }],
    groups: [group],
  };
}

let current: Snapshot;

/** Sends the next snapshot, a copy of the last one changed by `change`, as the event stream would. */
function next(change: (group: GroupSnapshot) => void): RenderedComponent[] {
  const snapshot = structuredClone(current);
  snapshot.revision = current.revision + 1;
  change(snapshot.groups[0]!);
  current = snapshot;
  tracker.reset();
  act(() => simulationStore.getState().ingestSnapshot(structuredClone(snapshot)));
  return tracker.commits.flat();
}

function namesOf(rendered: readonly RenderedComponent[], name: string): string[] {
  return rendered
    .filter((component) => component.name === name)
    .map((component) => component.keys.filter((key) => key.startsWith('consumer-') || key.startsWith('perf-')).reverse().join('/'))
    .sort();
}

const rowsOf = (rendered: readonly RenderedComponent[]) => namesOf(rendered, 'ConsumerRow');
const stripsOf = (rendered: readonly RenderedComponent[]) => namesOf(rendered, 'Strip');

/** The components that rendered outside the rows. */
function outsideRows(rendered: readonly RenderedComponent[]): string[] {
  return rendered
    .filter((component) => !component.keys.some((key) => key.startsWith('consumer-')))
    .map((component) => component.name)
    .sort();
}

/** The partition of `topicIndex` that `owner` owns first. */
function partitionOf(owner: number, topicIndex: number): number {
  const partition = Array.from({ length: PARTITIONS }, (_, p) => p).find((p) => ownerOf(topicIndex, p) === owner);
  if (partition === undefined) {
    throw new Error(`consumer-${owner} owns nothing of ${TOPICS[topicIndex]}`);
  }
  return partition;
}

function setLag(group: GroupSnapshot, topicIndex: number, partition: number, lag: number): void {
  group.lag[TOPICS[topicIndex]!]![partition] = lag;
}

beforeEach(() => {
  configStore.setState({ phase: 'RUNNING' });
  simulationStore.setState({ snapshot: null, status: null, connection: 'open', requestError: null });
  current = baseSnapshot();
  simulationStore.getState().ingestSnapshot(structuredClone(current));
  tracker.reset();
  render(<RowsView />);
});

afterEach(() => {
  cleanup();
  configStore.setState({ phase: 'IDLE' });
});

describe('re-renders of the Rows view', () => {
  it('renders every row and strip once on mount', () => {
    const rendered = tracker.commits.flat();

    expect(rowsOf(rendered)).toHaveLength(CONSUMERS);
    expect(stripsOf(rendered)).toHaveLength(CONSUMERS * TOPICS.length);
    expect(document.querySelectorAll('.pcell')).toHaveLength(CONSUMERS * TOPICS.length * PARTITIONS);
  });

  it('renders only the row and the strip of a partition whose lag changes', () => {
    const partition = partitionOf(3, 1);

    const rendered = next((group) => setLag(group, 1, partition, 42));

    expect(rowsOf(rendered)).toEqual(['consumer-3']);
    expect(stripsOf(rendered)).toEqual(['consumer-3/perf-2']);
    expect(outsideRows(rendered)).toEqual(['GroupHeader', 'RowsGrid', 'StatusItem', 'StatusItem']);
    const cell = document.querySelector(`[data-tip="perf-2 / p${partition} · assigned · lag 42"]`);
    expect(cell?.getAttribute('style')).toBe('--fill: 45%;');
  });

  it('renders the rows of the partitions whose lag changes, one strip each', () => {
    const rendered = next((group) => {
      setLag(group, 0, partitionOf(1, 0), 10);
      setLag(group, 2, partitionOf(1, 2), 10);
      setLag(group, 2, partitionOf(8, 2), 10);
    });

    expect(rowsOf(rendered)).toEqual(['consumer-1', 'consumer-8']);
    expect(stripsOf(rendered)).toEqual(['consumer-1/perf-1', 'consumer-1/perf-3', 'consumer-8/perf-3']);
  });

  it('renders only the row, and no strip, of a consumer whose status changes', () => {
    const rendered = next((group) => {
      group.consumers[5] = { ...group.consumers[5]!, status: 'FROZEN', faults: ['freeze'] };
    });

    expect(rowsOf(rendered)).toEqual(['consumer-5']);
    expect(stripsOf(rendered)).toEqual([]);
    expect(document.querySelectorAll('.row-panel.frozen')).toHaveLength(1);
  });

  it('renders only the strip of a partition whose state changes', () => {
    const partition = partitionOf(7, 2);

    const rendered = next((group) => {
      const target = group.consumers[7]!;
      target.partitions = target.partitions.map((view) =>
        view.topic === 'perf-3' && view.partition === partition ? { ...view, state: 'REVOKING' } : view,
      );
    });

    expect(rowsOf(rendered)).toEqual(['consumer-7']);
    expect(stripsOf(rendered)).toEqual(['consumer-7/perf-3']);
  });

  it('renders no row for a change of the group alone', () => {
    const rendered = next((group) => {
      group.state = 'RECONCILING';
      group.epoch = 4;
    });

    expect(rowsOf(rendered)).toEqual([]);
    expect(stripsOf(rendered)).toEqual([]);
    expect(outsideRows(rendered)).toEqual(['GroupHeader', 'RowsGrid', 'StatusItem', 'StatusItem']);
  });

  it('renders nothing for a snapshot that changes nothing but its revision', () => {
    const rendered = next(() => undefined);

    expect(rendered).toEqual([]);
  });

  it('renders every row when the lag of every consumer changes', () => {
    const rendered = next((group) => {
      for (const topic of TOPICS) {
        group.lag[topic] = group.lag[topic]!.map((lag) => lag + 1);
      }
    });

    expect(rowsOf(rendered)).toHaveLength(CONSUMERS);
    expect(stripsOf(rendered)).toHaveLength(CONSUMERS * TOPICS.length);
  });
});
