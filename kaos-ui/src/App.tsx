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

import type { CSSProperties } from 'react';

import { ConfigPanel } from './components/config/ConfigPanel';

// The layout of the prototype: the sidebar with the config panel, and the
// main area, which the Rows view fills in a later task.
const main: CSSProperties = {
  display: 'grid',
  placeItems: 'center',
  color: 'var(--text-muted)',
  fontFamily: 'var(--font-mono)',
  fontSize: '12px',
};

export function App() {
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-header">
          <div className="sidebar-brand">Kaos</div>
          <div className="sidebar-subtitle">Kafka Consumer Group Simulator</div>
        </div>
        <ConfigPanel />
      </aside>
      <main style={main}>Nothing to show yet</main>
    </div>
  );
}
