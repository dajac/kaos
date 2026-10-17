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

import config from './config.css?raw';
import rows from './rows.css?raw';
import tokens from './tokens.css?raw';

// The contrast of the dim texts against the background they are drawn on,
// opacity included, measured as WCAG 2.x does: AA asks for 4.5:1 for normal
// text. The colours are read from tokens.css.

type Rgb = readonly [number, number, number];

function token(name: string): Rgb {
  const match = new RegExp(`--${name}:\\s*#([0-9a-f]{6});`, 'i').exec(tokens);
  if (match === null) {
    throw new Error(`no colour token --${name}`);
  }
  const hex = match[1]!;
  return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16)) as unknown as Rgb;
}

/** `fg` drawn at `alpha` over `bg`. */
function over(fg: Rgb, alpha: number, bg: Rgb): Rgb {
  return fg.map((c, i) => c * alpha + bg[i]! * (1 - alpha)) as unknown as Rgb;
}

function luminance(rgb: Rgb): number {
  const [r, g, b] = rgb.map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  }) as unknown as Rgb;
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(fg: Rgb, bg: Rgb): number {
  const [hi, lo] = [luminance(fg), luminance(bg)].sort((a, b) => b - a) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

/** The opacity the rule `selector` of `css` gives, 1 when there is no such rule or it gives none. */
function opacityOf(css: string, selector: string): number {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) {
    return 1;
  }
  const body = css.slice(start, css.indexOf('}', start));
  const match = /opacity:\s*([0-9.]+)/.exec(body);
  return match === null ? 1 : Number(match[1]);
}

describe('text contrast', () => {
  const bg = token('bg');
  const surface = token('surface');
  const surface2 = token('surface2');
  const surface3 = token('surface3');
  const text = token('text');
  const muted = token('text-muted');
  const dim = token('text-dim');
  const white: Rgb = [255, 255, 255];
  const slate: Rgb = [107, 122, 141];
  // .row-axis: rgba(255,255,255,0.015) over --bg.
  const axis = over(white, 0.015, bg);

  const cases: readonly (readonly [string, Rgb, Rgb])[] = [
    ['--text-dim on --bg (legend, status labels)', dim, bg],
    ['--text-dim on --surface (field and section labels)', dim, surface],
    ['--text-dim on --surface2 (menu hints and group labels)', dim, surface2],
    ['--text-muted on --bg', muted, bg],
    ['--text-muted on --surface', muted, surface],
    ['--text-muted on --surface3 (Auto-Pilot, + Add)', muted, surface3],
    ['axis tick', over(dim, opacityOf(rows, '#rows-grid .row-axis-tick'), axis), axis],
    ['axis topic label', over(muted, opacityOf(rows, '#rows-grid .row-axis-topic-label'), axis), axis],
    // rgba(107,122,141,0.18) over --bg, the left overlay of a stopped row at
    // the opacity of the rule that dims it.
    [
      'STOPPED badge',
      over(muted, opacityOf(rows, '#rows-grid .row-panel.stopped .row-overlay-left'), over(slate, 0.18, bg)),
      over(slate, 0.18, bg),
    ],
    ['badge of an unknown state', dim, over(slate, 0.15, bg)],
    ['"Actions" at rest', over(muted, opacityOf(rows, '#rows-grid .dd-btn.row-actions-btn'), bg), bg],
    ['label of the locked panel', over(dim, opacityOf(config, '#config-mode.locked .config-scroll'), surface), surface],
    ['value of the locked panel', over(text, opacityOf(config, '#config-mode.locked .config-scroll'), surface), surface],
    ['meta label of a row', over(dim, opacityOf(rows, '#rows-grid .row-header-meta-label'), bg), bg],
    ['dim meta value of a row', over(dim, opacityOf(rows, '#rows-grid .row-header-meta-value.dim'), bg), bg],
  ];

  it.each(cases)('reaches 4.5:1 for %s', (_, fg, background) => {
    expect(contrast(fg, background)).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps --text-dim dimmer than --text-muted, and --text-muted dimmer than --text', () => {
    expect(luminance(dim)).toBeLessThan(luminance(muted));
    expect(luminance(muted)).toBeLessThan(luminance(text));
  });
});
