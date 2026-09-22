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

import { createContext, type MouseEvent } from 'react';

import { placeTooltip } from '../../model/tooltip';

// The one floating tooltip of the partition cells (`#pcell-tip`), as in the
// prototype: the grid delegates `mouseover` and `mouseout` to it, and it
// reads the text of the hovered cell from its `data-tip`. It is driven
// imperatively, so that hovering renders nothing.

export interface CellTooltip {
  readonly over: (event: MouseEvent<HTMLElement>) => void;
  readonly out: (event: MouseEvent<HTMLElement>) => void;
  /** Takes the text of the hovered cell again after a render, without moving the tooltip. */
  refresh(): void;
  hide(): void;
}

function cellOf(target: EventTarget | null): HTMLElement | null {
  return target instanceof Element ? (target.closest('.pcell') as HTMLElement | null) : null;
}

function tipOf(cell: HTMLElement): string {
  return cell.classList.contains('empty') ? '' : (cell.getAttribute('data-tip') ?? '');
}

/** The tooltip of the element that `element()` returns, once mounted. */
export function createCellTooltip(element: () => HTMLElement | null): CellTooltip {
  let current: HTMLElement | null = null;

  function hide(): void {
    element()?.classList.remove('show');
    current = null;
  }

  function show(cell: HTMLElement, text: string): void {
    const tip = element();
    if (tip === null) {
      return;
    }
    if (tip.textContent !== text) {
      tip.textContent = text;
    }
    // Measure it off-screen, with its final text, then place it.
    tip.classList.add('show');
    tip.style.left = '-9999px';
    tip.style.top = '-9999px';
    const position = placeTooltip(cell.getBoundingClientRect(), tip.getBoundingClientRect().width, window.innerWidth);
    tip.style.left = `${position.left}px`;
    tip.style.top = `${position.top}px`;
  }

  return {
    over(event) {
      const cell = cellOf(event.target);
      if (cell === null || !event.currentTarget.contains(cell)) {
        return;
      }
      const text = tipOf(cell);
      if (text === '') {
        return;
      }
      current = cell;
      show(cell, text);
    },

    out(event) {
      const cell = cellOf(event.target);
      if (cell === null) {
        return;
      }
      if (event.relatedTarget instanceof Node && cell.contains(event.relatedTarget)) {
        return;
      }
      if (current === cell) {
        hide();
      }
    },

    refresh() {
      const cell = current;
      const tip = element();
      if (cell === null || tip === null) {
        return;
      }
      const text = cell.isConnected ? tipOf(cell) : '';
      if (text === '') {
        hide();
      } else if (tip.textContent !== text) {
        tip.textContent = text;
      }
    },

    hide,
  };
}

/** The tooltip of the grid, for the strips to refresh after they render. */
export const CellTooltipContext = createContext<CellTooltip | null>(null);
