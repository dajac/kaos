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

// The lag gauge of a partition cell. A group snapshot carries one dense array
// of lags per subscribed topic, indexed by partition, where -1 is a lag that
// is not known yet.

/** The lag of a partition whose committed offset is not known yet. */
export const UNKNOWN_LAG = -1;

/** A lag at or above this value fills the gauge; below it, the fill is linear. */
export const LAG_MAX_SCALE = 100;

/** Lags are rounded up to a multiple of this step, so that a small backlog shows. */
export const LAG_ROUND_STEP = 5;

/** Whether `lag` is a known lag rather than {@link UNKNOWN_LAG}. */
export function isLagKnown(lag: number): boolean {
  return lag >= 0;
}

/**
 * The lag of `partition` of `topic` in the dense `lag` map of a group
 * snapshot, or {@link UNKNOWN_LAG} when the map has no value for it.
 */
export function lagOf(
  lag: Readonly<Record<string, readonly number[]>>,
  topic: string,
  partition: number,
): number {
  return lag[topic]?.[partition] ?? UNKNOWN_LAG;
}

/** `lag` rounded up to a multiple of {@link LAG_ROUND_STEP}; 0 for no lag and for an unknown one. */
export function roundLagUp(lag: number): number {
  if (!(lag > 0)) {
    return 0;
  }
  return Math.ceil(lag / LAG_ROUND_STEP) * LAG_ROUND_STEP;
}

/**
 * The fill of the gauge, a whole percentage from 0 to 100: the rounded lag
 * scaled to {@link LAG_MAX_SCALE}. No lag and an unknown lag fill nothing.
 */
export function lagFillPct(lag: number): number {
  return Math.min(100, Math.round((roundLagUp(lag) / LAG_MAX_SCALE) * 100));
}
