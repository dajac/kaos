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

import { memo } from 'react';

// The legend of the cell states, with the swatches of the prototype, coloured
// by `rows.css` from the fills of the cells. The lag gradient of the prototype
// belonged to the Bento view and is not shown.

const ITEMS: readonly { readonly label: string; readonly state: string }[] = [
  { label: 'Assigned', state: 'assigned' },
  { label: 'Pending', state: 'pending' },
  { label: 'Revoking', state: 'revoking' },
];

// Memoized: it takes no props, and the header renders again with every change
// of the group.
export const Legend = memo(function Legend() {
  return (
    <div className="legend">
      {ITEMS.map((item) => (
        <div key={item.label} className="legend-item">
          <div className={`legend-swatch ${item.state}`}></div> {item.label}
        </div>
      ))}
    </div>
  );
});
