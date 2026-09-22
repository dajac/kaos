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

// The partition-id axis under the heatmap strips: every `stride`-th partition
// has a tick, centred on its cell.

export interface AxisTick {
  /** The partition id that the tick shows. */
  readonly partition: number;
  /** The horizontal centre of the cell, in percent of the width of the topic band. */
  readonly centerPct: number;
}

/** Every how many partitions a tick is drawn, for a topic of `numPartitions` partitions. */
export function axisStride(numPartitions: number): number {
  if (numPartitions >= 100) {
    return 20;
  }
  if (numPartitions >= 40) {
    return 10;
  }
  if (numPartitions >= 15) {
    return 5;
  }
  return Math.max(1, Math.ceil(numPartitions / 4));
}

/** The ticks of the band of a topic of `numPartitions` partitions, from partition 0. */
export function axisTicks(numPartitions: number): AxisTick[] {
  const stride = axisStride(numPartitions);
  const ticks: AxisTick[] = [];
  for (let partition = 0; partition < numPartitions; partition += stride) {
    ticks.push({ partition, centerPct: ((partition + 0.5) / numPartitions) * 100 });
  }
  return ticks;
}
