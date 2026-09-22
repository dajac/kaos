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

// Structural sharing of JSON values: `shareStructure(prev, next)` is `next`,
// except that every part of it that equals the same part of `prev` is the
// object of `prev`. An unchanged snapshot thus keeps its consumers, lag
// arrays and groups by reference, and the memoized components that take them
// do not render again.
//
// The elements of an array are matched by their `id` when they all have a
// string one, so that removing a consumer keeps the objects of the others,
// and by position otherwise.

type Json = unknown;

function isObject(value: Json): value is Record<string, Json> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function idOf(value: Json): string | undefined {
  return isObject(value) && typeof value.id === 'string' ? value.id : undefined;
}

function shareArray(prev: readonly Json[], next: readonly Json[]): readonly Json[] {
  const nextIds = next.map(idOf);
  const byId = nextIds.every((id) => id !== undefined) && prev.every((value) => idOf(value) !== undefined);
  const previous = byId ? new Map(prev.map((value) => [idOf(value), value])) : undefined;

  let same = prev.length === next.length;
  const result = next.map((value, index) => {
    const old = previous === undefined ? prev[index] : previous.get(nextIds[index]);
    const shared = shareStructure(old, value);
    if (shared !== prev[index]) {
      same = false;
    }
    return shared;
  });
  return same ? prev : result;
}

function shareObject(prev: Record<string, Json>, next: Record<string, Json>): Record<string, Json> {
  const prevKeys = Object.keys(prev);
  const nextKeys = Object.keys(next);
  let same = prevKeys.length === nextKeys.length;
  const result: Record<string, Json> = {};
  for (const key of nextKeys) {
    const shared = shareStructure(prev[key], next[key]);
    if (shared !== prev[key] || !(key in prev)) {
      same = false;
    }
    result[key] = shared;
  }
  return same ? prev : result;
}

/** `next`, with the parts that equal those of `prev` replaced by the objects of `prev`. */
export function shareStructure<T>(prev: T, next: T): T {
  if (Object.is(prev, next)) {
    return prev;
  }
  if (Array.isArray(prev) && Array.isArray(next)) {
    return shareArray(prev, next) as T;
  }
  if (isObject(prev) && isObject(next)) {
    return shareObject(prev, next) as T;
  }
  return next;
}
