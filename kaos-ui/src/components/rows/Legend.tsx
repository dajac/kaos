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

import { memo, type CSSProperties } from 'react';

// The legend of the cell states, with the swatches of the prototype. The lag
// gradient of the prototype belonged to the Bento view and is not shown.

const ITEMS: readonly { readonly label: string; readonly swatch: CSSProperties }[] = [
  { label: 'Assigned', swatch: { background: 'var(--green-dim)', border: '1px solid rgba(52,211,153,0.25)' } },
  { label: 'Pending', swatch: { background: 'var(--accent-dim)', border: '1px solid rgba(76,154,255,0.3)' } },
  { label: 'Revoking', swatch: { background: 'var(--yellow-dim)', border: '1px solid rgba(251,191,36,0.35)' } },
];

// Memoized: it takes no props, and the header renders again with every change
// of the group.
export const Legend = memo(function Legend() {
  return (
    <div className="legend">
      {ITEMS.map((item) => (
        <div key={item.label} className="legend-item">
          <div className="legend-swatch" style={item.swatch}></div> {item.label}
        </div>
      ))}
    </div>
  );
});
