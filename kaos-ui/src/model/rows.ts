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

import type { components } from '../api/schema';
import { lagFillPct, lagOf } from './lag';
import { cellTooltipText } from './tooltip';

// What the Rows view shows of a group snapshot: the badges of the header and
// of each consumer, and the cells of the strips. The states come from the
// core (status, partition states, the out-of-group and mid-rebalance
// blanking); this module only turns them into the classes and texts of the
// prototype.

type GroupType = components['schemas']['GroupType'];
type GroupState = components['schemas']['GroupState'];
type ConsumerSnapshot = components['schemas']['ConsumerSnapshot'];
type ConsumerStatus = components['schemas']['ConsumerStatus'];
type PartitionState = components['schemas']['PartitionState'];
type PartitionView = components['schemas']['PartitionView'];

/** The placeholder of an unknown value. */
export const DASH = '—';

export interface Badge {
  readonly text: string;
  readonly cls: string;
}

/** The state badge of the header: `PREPARING REBALANCE`, with the class of its colour. */
export function groupStateBadge(state: GroupState): Badge {
  let cls = 's-empty';
  if (state === 'STABLE') {
    cls = 's-stable';
  } else if (
    state === 'RECONCILING' ||
    state === 'ASSIGNING' ||
    state === 'PREPARING_REBALANCE' ||
    state === 'COMPLETING_REBALANCE'
  ) {
    cls = 's-reconciling';
  }
  return { text: state.replace(/_/g, ' '), cls };
}

/** The type badge of the header; a dash until the coordinator reports the group. */
export function groupTypeLabel(type: GroupType): string {
  return type === 'UNKNOWN' ? DASH : type;
}

/** A number of the snapshot, where -1 is unknown, as the header and the meta show it. */
export function formatNumber(value: number): string {
  return value >= 0 ? String(value) : DASH;
}

/** The state badge of a consumer row, which also gives the class of its panel. */
export interface ConsumerBadge extends Badge {
  /** Whether the badge shows; a healthy classic member has none. */
  readonly show: boolean;
}

const STATUS_CLASSES: Record<ConsumerStatus, string> = {
  FAILED: 'stopping',
  STOPPED: 'stopped',
  FROZEN: 'frozen',
  ISOLATED: 'partitioned',
  STOPPING: 'stopping',
  JOINING: 'joining',
  RECONCILING: 'reconciling',
  STABLE: 'stable',
};

/** The statuses that a classic member shows as a badge: those of a fault or a stop. */
const CLASSIC_BADGES: ReadonlySet<ConsumerStatus> = new Set(['FAILED', 'STOPPED', 'FROZEN', 'ISOLATED']);

export function consumerBadge(consumer: Pick<ConsumerSnapshot, 'status' | 'protocol'>): ConsumerBadge {
  const { status } = consumer;
  return {
    show: consumer.protocol !== 'classic' || CLASSIC_BADGES.has(status),
    text: status === 'ISOLATED' ? 'PARTITIONED' : status,
    cls: STATUS_CLASSES[status],
  };
}

/** A cell of a strip: its class, the height of its gauge in percent, and its tooltip. */
export interface Cell {
  readonly state: PartitionState;
  readonly fillPct: number;
  readonly tip: string;
}

/** The states of the partitions of `topic` in `partitions`, by partition; the others are empty. */
export function topicStates(
  partitions: readonly PartitionView[],
  topic: string,
): ReadonlyMap<number, PartitionState> {
  const states = new Map<number, PartitionState>();
  for (const view of partitions) {
    if (view.topic === topic) {
      states.set(view.partition, view.state);
    }
  }
  return states;
}

/** The cells of the strip of `topic`, which has `numPartitions` partitions. */
export function stripCells(
  topic: string,
  numPartitions: number,
  states: ReadonlyMap<number, PartitionState>,
  lag: Readonly<Record<string, readonly number[]>>,
): Cell[] {
  const cells: Cell[] = [];
  for (let partition = 0; partition < numPartitions; partition++) {
    const state = states.get(partition) ?? 'EMPTY';
    if (state === 'EMPTY') {
      cells.push({ state, fillPct: 0, tip: '' });
    } else {
      const partitionLag = lagOf(lag, topic, partition);
      cells.push({ state, fillPct: lagFillPct(partitionLag), tip: cellTooltipText(topic, partition, state, partitionLag) });
    }
  }
  return cells;
}

/**
 * Whether `prev` and `next` give the same lag to each of `partitions`, the
 * partitions of a consumer: its row renders the lag of no other partition.
 */
export function sameLagOf(
  partitions: readonly PartitionView[],
  prev: Readonly<Record<string, readonly number[]>>,
  next: Readonly<Record<string, readonly number[]>>,
): boolean {
  return (
    prev === next ||
    partitions.every((view) => lagOf(prev, view.topic, view.partition) === lagOf(next, view.topic, view.partition))
  );
}

/** A string that changes whenever a cell of the strip changes, for `React.memo`. */
export function stripSignature(cells: readonly Cell[]): string {
  return cells.map((cell) => (cell.state === 'EMPTY' ? 'E' : `${cell.state[0]}${cell.tip}`)).join('|');
}

/** The class of a cell, as in the prototype. */
export function cellClass(state: PartitionState): string {
  return `pcell ${state.toLowerCase()}`;
}
