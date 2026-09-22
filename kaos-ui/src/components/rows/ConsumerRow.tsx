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

import { memo, useMemo } from 'react';

import type { ConsumerSnapshot } from '../../api/client';
import { consumerBadge, DASH, formatNumber, sameLagOf, stripCells, topicStates } from '../../model/rows';
import { ActionMenu } from './ActionMenu';
import { AxisBand, PartitionStrip } from './PartitionStrip';

// The row of a consumer: the strip is the row. The heatmap of each topic
// fills it, the axis rail runs below, and the name with the state badge (and,
// on hover, the member id, instance and epoch or generation) floats on the
// left, the "Actions ▾" menu on the right.

/** A subscribed topic and its number of partitions. */
export interface TopicBand {
  readonly name: string;
  readonly partitions: number;
}

interface ConsumerRowProps {
  readonly groupId: string;
  readonly consumer: ConsumerSnapshot;
  readonly topics: readonly TopicBand[];
  readonly lag: Readonly<Record<string, readonly number[]>>;
}

interface MetaItemProps {
  /** The class of the item in the prototype: `meta-protocol`, `meta-id`, `meta-instance` or `meta-epoch`. */
  readonly kind: string;
  readonly label: string;
  readonly value: string;
  /** The full value, as the title of a value that may be cut. */
  readonly title?: string;
}

function MetaItem({ kind, label, value, title }: MetaItemProps) {
  return (
    <span className={`row-header-meta-item ${kind}`}>
      <span className="row-header-meta-label">{label}</span>
      <span className={value === DASH ? 'row-header-meta-value dim' : 'row-header-meta-value'} title={title}>
        {value}
      </span>
    </span>
  );
}

const Separator = () => <span className="row-header-meta-sep">&middot;</span>;

// The lag map of the group changes with the lag of any partition; a row
// renders again only when the lag of one of its own partitions changes.
function sameProps(prev: ConsumerRowProps, next: ConsumerRowProps): boolean {
  return (
    prev.groupId === next.groupId &&
    prev.consumer === next.consumer &&
    prev.topics === next.topics &&
    sameLagOf(next.consumer.partitions, prev.lag, next.lag)
  );
}

export const ConsumerRow = memo(function ConsumerRow({ groupId, consumer, topics, lag }: ConsumerRowProps) {
  const badge = consumerBadge(consumer);
  const classic = consumer.protocol === 'classic';

  const cells = useMemo(
    () =>
      topics.map((topic) => stripCells(topic.name, topic.partitions, topicStates(consumer.partitions, topic.name), lag)),
    [topics, consumer.partitions, lag],
  );

  return (
    <div className={`row-panel ${badge.cls}`}>
      <div className="row-strip">
        {topics.map((topic, index) => (
          <PartitionStrip key={topic.name} cells={cells[index] ?? []} />
        ))}
      </div>
      <div className="row-axis">
        {topics.map((topic) => (
          <AxisBand key={topic.name} topic={topic.name} numPartitions={topic.partitions} />
        ))}
      </div>
      <div className="row-overlay-left">
        {badge.show ? (
          <span className={`row-state-badge ${badge.cls}`} title={consumer.failure === '' ? undefined : consumer.failure}>
            {badge.text}
          </span>
        ) : null}
        {/* The id, `consumer-0`, as the prototype and the config panel name
            it; the client id, unique across groups, is its title. */}
        <span className="member-name" title={consumer.clientId}>
          {consumer.id}
        </span>
        <div className="row-header-meta">
          <MetaItem kind="meta-protocol" label="protocol" value={consumer.protocol} />
          <Separator />
          <MetaItem
            kind="meta-id"
            label="id"
            value={consumer.memberId === '' ? DASH : consumer.memberId}
            title={consumer.memberId === '' ? undefined : consumer.memberId}
          />
          <Separator />
          <MetaItem
            kind="meta-instance"
            label="instance"
            value={consumer.instanceId === '' ? DASH : consumer.instanceId}
            title={consumer.instanceId === '' ? undefined : consumer.instanceId}
          />
          <Separator />
          {classic ? (
            <MetaItem kind="meta-epoch" label="generation" value={formatNumber(consumer.generation)} />
          ) : (
            <MetaItem kind="meta-epoch" label="epoch" value={formatNumber(consumer.memberEpoch)} />
          )}
        </div>
      </div>
      <div className="row-overlay-right">
        <ActionMenu groupId={groupId} consumer={consumer} />
      </div>
    </div>
  );
}, sameProps);
