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

// The empty shell: the sidebar and the main area of the prototype, drawn with
// the design tokens only. The screens replace it in later tasks.
const shell: CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'var(--sidebar-w) 1fr',
  height: '100vh',
};

const sidebar: CSSProperties = {
  background: 'var(--surface)',
  borderRight: '1px solid var(--border)',
  padding: '16px 16px 12px',
};

const brand: CSSProperties = {
  fontFamily: 'var(--font-mono)',
  fontSize: '15px',
  fontWeight: 700,
  letterSpacing: '2px',
  textTransform: 'uppercase',
  color: 'var(--accent)',
};

const subtitle: CSSProperties = {
  fontSize: '11px',
  color: 'var(--text-dim)',
  marginTop: '2px',
  letterSpacing: '0.3px',
};

const main: CSSProperties = {
  display: 'grid',
  placeItems: 'center',
  color: 'var(--text-muted)',
  fontFamily: 'var(--font-mono)',
  fontSize: '12px',
};

export function App() {
  return (
    <div style={shell}>
      <aside style={sidebar}>
        <div style={brand}>Kaos</div>
        <div style={subtitle}>Kafka Consumer Group Simulator</div>
      </aside>
      <main style={main}>Nothing to show yet</main>
    </div>
  );
}
