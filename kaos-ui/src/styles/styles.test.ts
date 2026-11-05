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

// Every colour comes from tokens.css, so that each theme sets all of them: no
// other stylesheet and no component holds a colour literal.

const styles = import.meta.glob<string>(['./*.css', '!./tokens.css'], { query: '?raw', import: 'default', eager: true });
const components = import.meta.glob<string>(['../**/*.tsx', '!../**/*.test.tsx'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

const LITERAL = /(?<![\w&-])#[0-9a-f]{3,8}(?![\w-])|\b(?:rgba?|hsla?)\(|(?<![\w-])(?:white|black)(?![\w-])/gi;

function literals(source: string): string[] {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  return code.match(LITERAL) ?? [];
}

describe('colour literals', () => {
  it('finds the files', () => {
    expect(styles['./rows.css']).toContain('var(--fill-assigned)');
    expect(Object.keys(components)).toContain('../components/rows/Legend.tsx');
  });

  for (const [file, source] of Object.entries({ ...styles, ...components })) {
    it(`are not in ${file}`, () => {
      expect(literals(source)).toEqual([]);
    });
  }
});
