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
// text. The colours and the opacities are read from the stylesheets.

type Rgb = readonly [number, number, number];

interface Rule {
  readonly selectors: readonly string[];
  readonly declarations: ReadonlyMap<string, string>;
}

/** The plain rules of `css`, in order; the blocks of at-rules are read as rules too. */
function parse(css: string): readonly Rule[] {
  const result: Rule[] = [];
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const [, selectors, body] of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const declarations = new Map<string, string>();
    for (const declaration of body!.split(';')) {
      const colon = declaration.indexOf(':');
      if (colon > 0) {
        declarations.set(declaration.slice(0, colon).trim(), declaration.slice(colon + 1).trim());
      }
    }
    result.push({ selectors: selectors!.split(',').map((s) => s.replace(/\s+/g, ' ').trim()), declarations });
  }
  return result;
}

const configRules = parse(config);
const rowsRules = parse(rows);

/** The value of `property` for `selector`, the last rule winning; fails when no rule has the selector. */
function valueOf(rules: readonly Rule[], selector: string, property: string): string | undefined {
  const matching = rules.filter((r) => r.selectors.includes(selector));
  if (matching.length === 0) {
    throw new Error(`no rule for ${selector}`);
  }
  return matching
    .map((r) => r.declarations.get(property))
    .filter((v) => v !== undefined)
    .at(-1);
}

function opacityOf(rules: readonly Rule[], selector: string): number {
  const value = valueOf(rules, selector, 'opacity');
  return value === undefined ? 1 : Number(value);
}

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

/** A `var(--token)` or an `rgba(...)` colour of the stylesheets, drawn over `bg`. */
function colour(value: string | undefined, bg: Rgb): Rgb {
  const variable = /^var\(--([a-z0-9-]+)\)$/.exec(value ?? '');
  if (variable !== null) {
    return token(variable[1]!);
  }
  const rgba = /^rgba\((\d+),\s*(\d+),\s*(\d+),\s*([0-9.]+)\)$/.exec(value ?? '');
  if (rgba !== null) {
    const [, r, g, b, a] = rgba.map(Number) as unknown as [number, number, number, number, number];
    return over([r, g, b], a, bg);
  }
  if (value === 'transparent') {
    return bg;
  }
  throw new Error(`unknown colour ${value}`);
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

/** The colour of the text of `selector`, at its opacity, over `bg`, and `bg`. */
function text(
  rules: readonly Rule[],
  selector: string,
  bg: Rgb,
  colourFrom: string = selector,
): readonly [Rgb, Rgb] {
  return [over(colour(valueOf(rules, colourFrom, 'color'), bg), opacityOf(rules, selector), bg), bg];
}

describe('text contrast', () => {
  const bg = token('bg');
  const surface = token('surface');
  const surface2 = token('surface2');
  const surface3 = token('surface3');
  const muted = token('text-muted');
  const dim = token('text-dim');
  const axis = colour(valueOf(rowsRules, '#rows-grid .row-axis', 'background'), bg);
  const stoppedBadge = colour(valueOf(rowsRules, '#rows-grid .row-state-badge.stopped', 'background'), bg);
  const unknownBadge = colour(valueOf(rowsRules, '#rows-grid .row-state-badge', 'background'), bg);
  const lockedInput = colour(valueOf(configRules, '#config-mode.locked .field-input', 'background'), surface);

  const cases: readonly (readonly [string, readonly [Rgb, Rgb]])[] = [
    ['--text-dim on --bg (legend, status labels)', [dim, bg]],
    ['--text-dim on --surface (field and section labels)', [dim, surface]],
    ['--text-dim on --surface2 (menu hints and group labels)', [dim, surface2]],
    ['--text-muted on --bg', [muted, bg]],
    ['--text-muted on --surface', [muted, surface]],
    ['--text-muted on --surface3 (Auto-Pilot, + Add)', [muted, surface3]],
    // The ticks take their colour from the topic they sit in.
    ['axis tick', text(rowsRules, '#rows-grid .row-axis-tick', axis, '#rows-grid .row-axis-topic')],
    ['axis topic label', text(rowsRules, '#rows-grid .row-axis-topic-label', axis)],
    ['STOPPED badge', text(rowsRules, '#rows-grid .row-state-badge.stopped', stoppedBadge)],
    ['badge of an unknown state', text(rowsRules, '#rows-grid .row-state-badge', unknownBadge)],
    ['"Actions" at rest', text(rowsRules, '#rows-grid .dd-btn.row-actions-btn', bg)],
    ['label of the locked panel', text(configRules, '.field-label', surface)],
    ['value of the locked panel', text(configRules, '.field-input', lockedInput)],
    ['meta label of a row', text(rowsRules, '#rows-grid .row-header-meta-label', bg)],
    ['dim meta value of a row', text(rowsRules, '#rows-grid .row-header-meta-value.dim', bg)],
  ];

  it.each(cases)('reaches 4.5:1 for %s', (_, [fg, background]) => {
    expect(contrast(fg, background)).toBeGreaterThanOrEqual(4.5);
  });

  it('dims only the buttons that edit the config in the locked panel', () => {
    const dimmed = configRules
      .filter((r) => r.declarations.has('opacity'))
      .flatMap((r) => r.selectors)
      .filter((s) => s.startsWith('#config-mode.locked'));
    expect(dimmed.sort()).toEqual(
      [
        '#config-mode.locked .btn-add-consumer',
        '#config-mode.locked .dd-btn-add',
        '#config-mode.locked .field-remove',
        '#config-mode.locked .section-toggle:hover .section-remove',
      ].sort(),
    );
  });

  it('dims only the strip of a stopped row', () => {
    const dimmed = rowsRules
      .filter((r) => r.declarations.has('opacity'))
      .flatMap((r) => r.selectors)
      .filter((s) => s.includes('.stopped'));
    expect(dimmed).toEqual(['#rows-grid .row-panel.stopped .row-strip']);
  });

  it('sets a stopped row apart from a running row', () => {
    expect(opacityOf(rowsRules, '#rows-grid .row-panel.stopped .row-strip')).toBeLessThan(1);
    for (const property of ['background', 'color']) {
      expect(valueOf(rowsRules, '#rows-grid .row-state-badge.stopped', property)).not.toEqual(
        valueOf(rowsRules, '#rows-grid .row-state-badge.stable', property),
      );
    }
    expect(valueOf(rowsRules, '#rows-grid .row-panel.stopped', 'border-color')).toBeDefined();
  });

  it('keeps --text-dim dimmer than --text-muted, and --text-muted dimmer than --text', () => {
    expect(luminance(dim)).toBeLessThan(luminance(muted));
    expect(luminance(muted)).toBeLessThan(luminance(token('text')));
  });
});
