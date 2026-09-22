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

import { useState } from 'react';

import { parseIntInput } from '../../model/configModel';

// An integer input (`.field-input`). It keeps the text as typed, so that it
// can be cleared while editing, and sends `parseIntInput` of it, as the
// prototype read its inputs. A value set from outside replaces the text.

export interface NumberInputProps {
  readonly value: number;
  readonly min?: number | undefined;
  readonly max?: number | undefined;
  readonly onChange: (value: number) => void;
}

export function NumberInput({ value, min, max, onChange }: NumberInputProps) {
  const [text, setText] = useState(String(value));
  if (parseIntInput(text) !== value) {
    setText(String(value));
  }
  return (
    <input
      type="number"
      className="field-input"
      value={text}
      min={min}
      max={max}
      onChange={(event) => {
        setText(event.target.value);
        onChange(parseIntInput(event.target.value));
      }}
    />
  );
}
