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

import { shareStructure } from './share';
import { consumer, group, snapshot } from './snapshot.fixture';

// A deep copy, as a new event of the stream is.
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

describe('shareStructure', () => {
  it('keeps the previous value when nothing changed', () => {
    const prev = snapshot();

    expect(shareStructure(prev, copy(prev))).toBe(prev);
  });

  it('keeps the unchanged consumers and lag arrays of a changed snapshot', () => {
    const prev = snapshot();
    const next = copy(prev);
    next.revision = 2;
    const nextGroup = next.groups[0];
    if (nextGroup === undefined) {
      throw new Error('no group');
    }
    nextGroup.consumers[1] = consumer('consumer-1', { protocol: 'classic', memberEpoch: -1, status: 'RECONCILING' });
    nextGroup.lag['orders-1'] = [0, 13, -1, 3];

    const shared = shareStructure(prev, next);
    const prevGroup = prev.groups[0];

    expect(shared).toEqual(next);
    expect(shared).not.toBe(prev);
    expect(shared.groups[0]).not.toBe(prevGroup);
    expect(shared.groups[0]?.consumers[0]).toBe(prevGroup?.consumers[0]);
    expect(shared.groups[0]?.consumers[1]).not.toBe(prevGroup?.consumers[1]);
    expect(shared.groups[0]?.lag['orders-2']).toBe(prevGroup?.lag['orders-2']);
    expect(shared.groups[0]?.lag['orders-1']).not.toBe(prevGroup?.lag['orders-1']);
    expect(shared.topics).toBe(prev.topics);
  });

  it('matches the elements of an array by their id', () => {
    const prev = group();
    const next = copy(prev);
    next.consumers.shift();

    const shared = shareStructure(prev, next);

    expect(shared.consumers).toHaveLength(1);
    expect(shared.consumers[0]).toBe(prev.consumers[1]);
  });

  it('matches by position the elements without an id', () => {
    const prev = [{ topic: 'a', partition: 0 }, { topic: 'a', partition: 1 }];
    const next = [{ topic: 'a', partition: 0 }, { topic: 'a', partition: 2 }];

    const shared = shareStructure(prev, next);

    expect(shared[0]).toBe(prev[0]);
    expect(shared[1]).toEqual(next[1]);
    expect(shared[1]).not.toBe(prev[1]);
  });

  it('sees an added or a removed key', () => {
    const prev: Record<string, number> = { a: 1 };

    expect(shareStructure(prev, { a: 1, b: 2 })).toEqual({ a: 1, b: 2 });
    expect(shareStructure({ a: 1, b: 2 } as Record<string, number>, { a: 1 })).toEqual({ a: 1 });
    expect(shareStructure<unknown>(null, prev)).toBe(prev);
  });
});
