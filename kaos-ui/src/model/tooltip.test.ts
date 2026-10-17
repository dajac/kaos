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

import { cellTooltipText, placeTooltip } from './tooltip';

describe('placeTooltip', () => {
  // Parity with `positionPcellTip(cell)` of simulator.html on initial-setup,
  // run with a stub cell and tooltip: the cell rectangle, the measured
  // width of the tooltip and the width of the viewport, then the `left` and
  // `top` the prototype sets.
  it.each([
    // centred below the cell
    [{ left: 100, width: 20, bottom: 50 }, 160, 1000, { left: 110, top: 58 }],
    // clamped to the left edge
    [{ left: 0, width: 20, bottom: 50 }, 160, 1000, { left: 84, top: 58 }],
    [{ left: 70, width: 20, bottom: 50 }, 160, 1000, { left: 84, top: 58 }],
    [{ left: 74, width: 12, bottom: 30 }, 160, 1000, { left: 84, top: 38 }],
    // exactly at the left bound
    [{ left: 76, width: 16, bottom: 30 }, 160, 1000, { left: 84, top: 38 }],
    // clamped to the right edge
    [{ left: 990, width: 10, bottom: 50 }, 160, 1000, { left: 916, top: 58 }],
    [{ left: 900, width: 20, bottom: 50 }, 200, 1000, { left: 896, top: 58 }],
    // a tooltip that measures 0 px is taken as 160 px wide
    [{ left: 100, width: 20, bottom: 50 }, 0, 1000, { left: 110, top: 58 }],
    [{ left: 0, width: 20, bottom: 50 }, 0, 1000, { left: 84, top: 58 }],
    // wider than the viewport: the right edge wins
    [{ left: 100, width: 20, bottom: 50 }, 160, 0, { left: -84, top: 58 }],
    [{ left: 0, width: 10, bottom: 0 }, 300, 200, { left: 46, top: 8 }],
  ])('places the tooltip of %o (%d px, viewport %d px)', (cell, tipWidth, viewportWidth, expected) => {
    expect(placeTooltip(cell, tipWidth, viewportWidth)).toEqual(expected);
  });
});

describe('cellTooltipText', () => {
  // The tooltip text of `updateStripCellsNew(panel, m, topics, numPartitions,
  // partitionLag)` of simulator.html on initial-setup, except for the lag: no
  // text for an empty cell, and the lag whenever it is known, 0 included.
  it.each([
    ['kaos-1', 3, 'ASSIGNED', 12, 'kaos-1 / p3 · assigned · lag 12'],
    ['kaos-1', 3, 'ASSIGNED', 0, 'kaos-1 / p3 · assigned · lag 0'],
    ['kaos-1', 3, 'ASSIGNED', -1, 'kaos-1 / p3 · assigned'],
    ['kaos-2', 0, 'PENDING', 5, 'kaos-2 / p0 · pending · lag 5'],
    ['kaos-2', 7, 'REVOKING', 1, 'kaos-2 / p7 · revoking · lag 1'],
    ['kaos-2', 7, 'EMPTY', 9, ''],
  ] as const)('describes %s/%d %s with lag %d', (topic, partition, state, lag, text) => {
    expect(cellTooltipText(topic, partition, state, lag)).toBe(text);
  });
});
