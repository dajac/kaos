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

import { axisStride, axisTicks } from './axis';

describe('axisStride', () => {
  // Parity with `pickAxisStrideNew(numPartitions)` of simulator.html on
  // initial-setup, run on these inputs.
  it.each([
    [1, 1],
    [2, 1],
    [3, 1],
    [4, 1],
    [5, 2],
    [8, 2],
    [9, 3],
    [13, 4],
    [14, 4],
    [15, 5],
    [36, 5],
    [39, 5],
    [40, 10],
    [99, 10],
    [100, 20],
    [120, 20],
  ])('draws a tick every %d partitions as %d', (numPartitions, stride) => {
    expect(axisStride(numPartitions)).toBe(stride);
  });
});

describe('axisTicks', () => {
  // Parity with the tick loop of `layoutAxisNew(panel, numTopics,
  // numPartitions)` of simulator.html on initial-setup: the partition and its
  // `left` percentage, which the prototype prints with `toFixed(2)`.
  it.each<[number, [number, string][]]>([
    [1, [[0, '50.00']]],
    [
      5,
      [
        [0, '10.00'],
        [2, '50.00'],
        [4, '90.00'],
      ],
    ],
    [
      36,
      [
        [0, '1.39'],
        [5, '15.28'],
        [10, '29.17'],
        [15, '43.06'],
        [20, '56.94'],
        [25, '70.83'],
        [30, '84.72'],
        [35, '98.61'],
      ],
    ],
    [
      100,
      [
        [0, '0.50'],
        [20, '20.50'],
        [40, '40.50'],
        [60, '60.50'],
        [80, '80.50'],
      ],
    ],
  ])('places the ticks of %d partitions', (numPartitions, expected) => {
    expect(axisTicks(numPartitions).map((t) => [t.partition, t.centerPct.toFixed(2)])).toEqual(expected);
  });

  it('draws no tick without partitions', () => {
    expect(axisTicks(0)).toEqual([]);
  });
});
