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

// The floating tooltip of a partition cell: always below the cell, centred on
// it, and clamped so that it stays inside the viewport. The caller measures
// the cell and the tooltip; the tooltip is drawn centred on `left`.

type PartitionState = components['schemas']['PartitionState'];

/** The gap between the bottom of the cell and the top of the tooltip, in px. */
export const TOOLTIP_GAP = 8;

/** The smallest distance between the tooltip and an edge of the viewport, in px. */
export const TOOLTIP_MARGIN = 4;

/** The width assumed for a tooltip that measures 0 px wide. */
export const TOOLTIP_FALLBACK_WIDTH = 160;

/** The viewport rectangle of a cell, as `getBoundingClientRect()` gives it. */
export interface CellRect {
  readonly left: number;
  readonly width: number;
  readonly bottom: number;
}

/** Where the tooltip goes: `left` is its horizontal centre and `top` its top edge, in px. */
export interface TooltipPosition {
  readonly left: number;
  readonly top: number;
}

/**
 * The position of a tooltip `tipWidth` px wide for `cell`, in a viewport
 * `viewportWidth` px wide. When the tooltip is wider than the viewport allows,
 * the right edge wins.
 */
export function placeTooltip(cell: CellRect, tipWidth: number, viewportWidth: number): TooltipPosition {
  const width = tipWidth || TOOLTIP_FALLBACK_WIDTH;
  const minLeft = TOOLTIP_MARGIN + width / 2;
  const maxLeft = viewportWidth - TOOLTIP_MARGIN - width / 2;
  let left = cell.left + cell.width / 2;
  if (left < minLeft) {
    left = minLeft;
  }
  if (left > maxLeft) {
    left = maxLeft;
  }
  return { left, top: cell.bottom + TOOLTIP_GAP };
}

const STATE_LABELS: Record<PartitionState, string> = {
  ASSIGNED: 'assigned',
  PENDING: 'pending',
  REVOKING: 'revoking',
  EMPTY: 'empty',
};

/**
 * The text of the tooltip of a cell, or the empty string for an empty cell,
 * which has none. A known lag, 0 included, is appended; `-1`, an unknown lag,
 * is not.
 */
export function cellTooltipText(topic: string, partition: number, state: PartitionState, lag: number): string {
  if (state === 'EMPTY') {
    return '';
  }
  const text = `${topic} / p${partition} · ${STATE_LABELS[state]}`;
  return lag >= 0 ? `${text} · lag ${lag}` : text;
}
