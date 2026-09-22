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

import { isLagKnown, lagFillPct, lagOf, roundLagUp, UNKNOWN_LAG } from './lag';

describe('lagFillPct', () => {
  // Parity with `lagFillPct(lag)` of simulator.html on initial-setup, run on
  // these inputs. The prototype reads a missing lag (null) as 0; the snapshot
  // gives -1 for an unknown lag instead, which fills nothing too.
  it.each([
    [UNKNOWN_LAG, 0],
    [0, 0],
    [0.5, 5],
    [1, 5],
    [4, 5],
    [5, 5],
    [6, 10],
    [10, 10],
    [11, 15],
    [42, 45],
    [95, 95],
    [96, 100],
    [99, 100],
    [100, 100],
    [101, 100],
    [1000, 100],
  ])('fills %d as %d%%', (lag, pct) => {
    expect(lagFillPct(lag)).toBe(pct);
  });
});

describe('roundLagUp', () => {
  it.each([
    [UNKNOWN_LAG, 0],
    [-7, 0],
    [0, 0],
    [1, 5],
    [5, 5],
    [6, 10],
    [123, 125],
  ])('rounds %d up to %d', (lag, rounded) => {
    expect(roundLagUp(lag)).toBe(rounded);
  });
});

describe('isLagKnown', () => {
  it.each([
    [UNKNOWN_LAG, false],
    [0, true],
    [12, true],
  ])('%d is known: %s', (lag, known) => {
    expect(isLagKnown(lag)).toBe(known);
  });
});

describe('lagOf', () => {
  const lag = { 'kaos-1': [0, 7, -1], 'kaos-2': [3] };

  it.each([
    ['kaos-1', 0, 0],
    ['kaos-1', 1, 7],
    ['kaos-1', 2, UNKNOWN_LAG],
    ['kaos-1', 3, UNKNOWN_LAG],
    ['kaos-2', 0, 3],
    ['other', 0, UNKNOWN_LAG],
  ])('reads %s/%d as %d', (topic, partition, expected) => {
    expect(lagOf(lag, topic, partition)).toBe(expected);
  });
});
