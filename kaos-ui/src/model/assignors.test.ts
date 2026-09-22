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

import {
  adaptAssignor,
  assignorOptions,
  assignorOptionValue,
  CUSTOM_OPTION,
  firstPreset,
  isCustomAllowed,
  isCustomAssignor,
  isPresetAssignor,
  isValidAssignor,
  pickAssignorOption,
} from './assignors';
import { META } from './meta.fixture';

describe('assignorOptions', () => {
  // Parity with `ASSIGNOR_OPTIONS` of simulator.html on initial-setup
  // (consumer: uniform, range, custom; classic: range, roundrobin, sticky,
  // cooperative-sticky), read from `/api/v1/meta`.
  it('offers the presets and custom for the consumer protocol', () => {
    expect(assignorOptions(META, 'consumer')).toEqual([
      { value: 'uniform', label: 'uniform' },
      { value: 'range', label: 'range' },
      { value: CUSTOM_OPTION, label: 'custom' },
    ]);
  });

  it('offers the presets for the classic protocol', () => {
    expect(assignorOptions(META, 'classic')).toEqual(
      ['range', 'roundrobin', 'sticky', 'cooperative-sticky'].map((name) => ({ value: name, label: name })),
    );
  });

  it('takes the first preset of a protocol', () => {
    expect(firstPreset(META, 'consumer')).toBe('uniform');
    expect(firstPreset(META, 'classic')).toBe('range');
    const noNames = { ...META, assignors: { ...META.assignors, classic: { names: [], customAllowed: false } } };
    expect(firstPreset(noNames, 'classic')).toBe('');
  });
});

describe('isValidAssignor', () => {
  // Parity with `isValidAssignor(protocol, value)` of simulator.html on
  // initial-setup, run on these inputs.
  it.each([
    ['consumer', 'anything', true],
    ['consumer', '', true],
    ['classic', 'range', true],
    ['classic', 'cooperative-sticky', true],
    ['classic', 'uniform', false],
    ['classic', '', false],
  ] as const)('%s accepts %j: %s', (protocol, name, valid) => {
    expect(isValidAssignor(META, protocol, name)).toBe(valid);
  });
});

describe('isPresetAssignor and isCustomAllowed', () => {
  it.each([
    ['consumer', 'uniform', true],
    ['consumer', '', false],
    ['consumer', 'sticky', false],
    ['classic', 'sticky', true],
    ['classic', 'uniform', false],
  ] as const)('%s has the preset %j: %s', (protocol, name, isPreset) => {
    expect(isPresetAssignor(META, protocol, name)).toBe(isPreset);
  });

  it('accepts a custom name for the consumer protocol only', () => {
    expect(isCustomAllowed(META, 'consumer')).toBe(true);
    expect(isCustomAllowed(META, 'classic')).toBe(false);
  });
});

describe('assignorOptionValue and isCustomAssignor', () => {
  // Parity with `assignorDropdownValue(protocol, value)` and
  // `isCustomAssignor(protocol, value)` of simulator.html on initial-setup,
  // run on these inputs.
  it.each([
    ['consumer', 'uniform', 'uniform', false],
    ['consumer', 'range', 'range', false],
    ['consumer', 'foo', CUSTOM_OPTION, true],
    ['consumer', '', CUSTOM_OPTION, true],
    ['classic', 'range', 'range', false],
    ['classic', 'sticky', 'sticky', false],
    ['classic', 'foo', 'foo', false],
  ] as const)('%s shows %j as %j (custom: %s)', (protocol, name, value, custom) => {
    expect(assignorOptionValue(META, protocol, name)).toBe(value);
    expect(isCustomAssignor(META, protocol, name)).toBe(custom);
  });
});

describe('pickAssignorOption', () => {
  // Parity with `setGroupAssignor(value)` of simulator.html on initial-setup,
  // run on these inputs: picking a preset selects it; picking custom starts an
  // empty name when leaving a preset and keeps a custom name already there.
  it.each([
    ['uniform', 'range', 'range'],
    ['uniform', CUSTOM_OPTION, ''],
    ['range', CUSTOM_OPTION, ''],
    ['sticky', CUSTOM_OPTION, 'sticky'],
    ['my-assignor', CUSTOM_OPTION, 'my-assignor'],
    ['', CUSTOM_OPTION, ''],
    ['my-assignor', 'uniform', 'uniform'],
  ])('from %j, picking %j gives %j', (current, option, expected) => {
    expect(pickAssignorOption(META, 'consumer', current, option)).toBe(expected);
  });
});

describe('adaptAssignor', () => {
  // Parity with `setGroupProtocol(value)` of simulator.html on initial-setup,
  // run on these inputs: an assignor that the new protocol accepts stays;
  // another falls back to the first preset of the protocol.
  it.each([
    ['classic', 'uniform', 'range'],
    ['classic', 'mine', 'range'],
    ['classic', '', 'range'],
    ['classic', 'sticky', 'sticky'],
    ['consumer', 'sticky', 'sticky'],
    ['consumer', 'mine', 'mine'],
    ['consumer', '', ''],
  ] as const)('under %s, %j becomes %j', (protocol, name, expected) => {
    expect(adaptAssignor(META, protocol, name)).toBe(expected);
  });
});
