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

import { memo, useContext, useEffect, type CSSProperties } from 'react';

import { axisTicks } from '../../model/axis';
import { cellClass, stripSignature, type Cell } from '../../model/rows';
import { CellTooltipContext } from './cellTooltip';

// The heatmap of one topic in a consumer row: one plain `.pcell` per
// partition, whose gauge is the `--fill` custom property. It renders again
// only when its signature, the states and tooltips of its cells, changes.

interface PartitionStripProps {
  readonly cells: readonly Cell[];
}

function fillStyle(fillPct: number): CSSProperties | undefined {
  return fillPct > 0 ? ({ '--fill': `${fillPct}%` } as CSSProperties) : undefined;
}

function Strip({ cells }: PartitionStripProps) {
  const tooltip = useContext(CellTooltipContext);

  // The hovered cell may have a new text, or be gone.
  useEffect(() => {
    tooltip?.refresh();
  });
  useEffect(() => () => tooltip?.refresh(), [tooltip]);

  return (
    <div className="row-strip-topic">
      <div className="row-strip-cells">
        {cells.map((cell, partition) => (
          <div
            key={partition}
            className={cellClass(cell.state)}
            style={fillStyle(cell.fillPct)}
            data-tip={cell.tip === '' ? undefined : cell.tip}
          />
        ))}
      </div>
    </div>
  );
}

export const PartitionStrip = memo(
  Strip,
  (prev, next) => prev.cells === next.cells || stripSignature(prev.cells) === stripSignature(next.cells),
);

interface AxisBandProps {
  readonly topic: string;
  readonly numPartitions: number;
}

/** The band of a topic in the axis rail: its name, then a tick every few partitions. */
export const AxisBand = memo(function AxisBand({ topic, numPartitions }: AxisBandProps) {
  return (
    <div className="row-axis-topic">
      <span className="row-axis-topic-label" title={topic}>
        {topic}
      </span>
      {axisTicks(numPartitions).map((tick) => (
        <span key={tick.partition} className="row-axis-tick" style={{ left: `${tick.centerPct.toFixed(2)}%` }}>
          {tick.partition}
        </span>
      ))}
    </div>
  );
});
