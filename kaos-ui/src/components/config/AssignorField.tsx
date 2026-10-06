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

import { useState, type ReactNode } from 'react';

import type { Meta } from '../../api/client';
import type { components } from '../../api/schema';
import { assignorOptions, CUSTOM_OPTION, isCustomAllowed, isCustomAssignor } from '../../model/assignors';
import { metaField } from '../../model/configModel';
import { Dropdown } from './Dropdown';
import { FieldError } from './FieldError';

type Protocol = components['schemas']['Protocol'];

// The assignor row: the presets of the protocol, and, for a custom name, a
// text input under the dropdown. The row then anchors its label to the top.
// The input stays while it has the focus, even when the name typed so far is
// a preset (`range` on the way to `range-sticky`): as in the prototype, which
// took the name on `change`, the name shows as a preset once the input loses
// the focus.

export interface AssignorFieldProps {
  readonly meta: Meta;
  readonly protocol: Protocol;
  /** The assignor name. */
  readonly value: string;
  /** An option of the dropdown was picked: a preset, or `CUSTOM_OPTION`. */
  readonly onPick: (option: string) => void;
  /** A custom name was typed in the text input. */
  readonly onCustomName: (name: string) => void;
  /** The × of an override, next to the dropdown. */
  readonly remove?: ReactNode;
  readonly error?: string | undefined;
}

export function AssignorField({ meta, protocol, value, onPick, onCustomName, remove, error }: AssignorFieldProps) {
  const [typing, setTyping] = useState(false);
  const custom = isCustomAssignor(meta, protocol, value) || (typing && isCustomAllowed(meta, protocol));
  const dropdown = (
    <Dropdown options={assignorOptions(meta, protocol)} value={custom ? CUSTOM_OPTION : value} onChange={onPick} />
  );
  const input = custom ? (
    <input
      type="text"
      className="field-input"
      style={{ marginTop: '6px' }}
      value={value}
      placeholder="custom assignor name"
      onChange={(event) => onCustomName(event.target.value)}
      onFocus={() => setTyping(true)}
      onBlur={() => setTyping(false)}
    />
  ) : null;

  return (
    <div className={custom ? 'field field-inline field-stacked' : 'field field-inline'}>
      <label className="field-label">{metaField(meta, 'CONSUMER', 'assignor').label}</label>
      {remove === undefined ? (
        <div>
          {dropdown}
          {input}
          <FieldError message={error} />
        </div>
      ) : (
        <>
          <div className="field-control">
            {dropdown}
            {remove}
          </div>
          {input}
          <FieldError message={error} />
        </>
      )}
    </div>
  );
}
