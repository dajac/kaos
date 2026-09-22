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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the sidebar of the prototype with the config panel', () => {
    const html = renderToStaticMarkup(<App />);

    expect(html).toContain('<div class="app"><aside class="sidebar">');
    expect(html).toContain('<div class="sidebar-brand">Kaos</div>');
    expect(html).toContain('Kafka Consumer Group Simulator');
    expect(html).toContain('<div id="config-mode">');
    // Nothing is loaded yet: Start waits for /meta.
    expect(html).toContain('<button class="btn btn-start" id="btn-start-sim" disabled="">Start</button>');
  });
});
