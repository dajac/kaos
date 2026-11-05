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

import tokensCss from './tokens.css?raw';

// The texts of the light theme meet the contrast of WCAG AA (4.5:1) on every
// surface they sit on. The tokens are read from tokens.css: the light block
// over the dark one, a `color-mix(... transparent)` laid over its surface.

type Rgba = readonly [number, number, number, number];

function block(selector: string): Map<string, string> {
  const start = tokensCss.indexOf(`${selector} {`);
  const body = tokensCss.slice(start, tokensCss.indexOf('}', start));
  return new Map([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(([, name = '', value = '']) => [name, value.trim()]));
}

const light = new Map([...block(':root'), ...block(':root[data-theme="light"]')]);

function color(value: string): Rgba {
  const hex = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(value);
  if (hex) {
    const [, r = '', g = '', b = ''] = hex;
    return [parseInt(r, 16), parseInt(g, 16), parseInt(b, 16), 1];
  }
  const ref = /^var\(--([\w-]+)\)$/.exec(value);
  if (ref) {
    return token(ref[1] ?? '');
  }
  const mix = /^color-mix\(in srgb, var\(--([\w-]+)\) ([\d.]+)%, transparent\)$/.exec(value);
  if (mix) {
    const [, name = '', percent = ''] = mix;
    const [r, g, b, a] = token(name);
    return [r, g, b, (a * Number(percent)) / 100];
  }
  throw new Error(`unsupported colour ${value}`);
}

function token(name: string): Rgba {
  const value = light.get(name);
  if (value === undefined) {
    throw new Error(`no token --${name}`);
  }
  return color(value);
}

function over([r, g, b, a]: Rgba, [br, bg, bb]: Rgba): Rgba {
  return [r * a + br * (1 - a), g * a + bg * (1 - a), b * a + bb * (1 - a), 1];
}

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

function luminance([r, g, b]: Rgba): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(text: string, background: string, surface: string): number {
  const fore = luminance(token(text));
  const back = luminance(over(token(background), token(surface)));
  return (Math.max(fore, back) + 0.05) / (Math.min(fore, back) + 0.05);
}

const SURFACES = ['bg', 'surface', 'surface2', 'surface3'];
const STATES = ['accent', 'green', 'yellow', 'red', 'fault', 'orange'];

// [text, background, the surface under a translucent background]
const PAIRS: [string, string, string][] = [
  ...['text', 'text-muted', 'text-dim'].flatMap((text) => SURFACES.map((s): [string, string, string] => [text, s, s])),
  // The state texts: alone on the page and in the sidebar, on their badge, and in the banners.
  ...STATES.flatMap((state) =>
    ['bg', 'surface'].flatMap((s): [string, string, string][] => [
      [state, s, s],
      [state, `${state}-dim`, s],
    ]),
  ),
  ['red', 'surface2', 'surface2'],
  ['yellow', 'surface2', 'surface2'],
  // Start and Stop.
  ['on-solid', 'green', 'surface'],
  ['on-solid', 'red', 'surface'],
  // The member name over a full cell.
  ['text', 'fill-assigned', 'bg'],
  ['text', 'fill-pending', 'bg'],
  ['text', 'fill-revoking', 'bg'],
];

describe('the light theme', () => {
  for (const [text, background, surface] of PAIRS) {
    it(`reads --${text} on --${background} over --${surface}`, () => {
      expect(contrast(text, background, surface)).toBeGreaterThanOrEqual(4.5);
    });
  }
});
