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
  assignorChoice,
  assignorLabel,
  assignorOptions,
  assignorOptionValue,
  CUSTOM_OPTION,
  customAssignor,
  DEFAULT_CHOICE,
  isCustomAllowed,
  isCustomChoice,
  isPresetAssignor,
  isValidAssignor,
  pickAssignorOption,
  type AssignorChoice,
} from './assignors';
import { META } from './meta.fixture';

const preset = (name: string): AssignorChoice => ({ name, custom: false });

describe('assignorOptions', () => {
  // Parity with `ASSIGNOR_OPTIONS` of simulator.html on initial-setup
  // (consumer: uniform, range, custom; classic: range, roundrobin, sticky,
  // cooperative-sticky), read from `/api/v1/meta`, after the default of the
  // protocol, the empty name.
  it('offers the default, the presets and custom for the consumer protocol', () => {
    expect(assignorOptions(META, 'consumer')).toEqual([
      { value: '', label: 'default' },
      { value: 'uniform', label: 'uniform' },
      { value: 'range', label: 'range' },
      { value: CUSTOM_OPTION, label: 'custom' },
    ]);
  });

  it('offers the default and the presets for the classic protocol', () => {
    expect(assignorOptions(META, 'classic').map((o) => o.value)).toEqual([
      '',
      'range',
      'roundrobin',
      'sticky',
      'cooperative-sticky',
    ]);
  });
});

describe('isValidAssignor', () => {
  // Parity with `isValidAssignor(protocol, value)` of simulator.html on
  // initial-setup, run on these inputs. The empty name is valid for both
  // protocols: it is the default of the protocol.
  it.each([
    ['consumer', 'anything', true],
    ['consumer', '', true],
    ['classic', 'range', true],
    ['classic', 'cooperative-sticky', true],
    ['classic', 'uniform', false],
    ['classic', '', true],
  ] as const)('%s accepts %j: %s', (protocol, name, valid) => {
    expect(isValidAssignor(META, protocol, name)).toBe(valid);
  });
});

describe('isPresetAssignor and isCustomAllowed', () => {
  it.each([
    ['consumer', '', true],
    ['consumer', 'uniform', true],
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

describe('assignorOptionValue and isCustomChoice', () => {
  // Parity with `assignorDropdownValue(protocol, value)` and
  // `isCustomAssignor(protocol, value)` of simulator.html on initial-setup,
  // run on these inputs. The empty name is the default preset; a custom name
  // being typed is flagged, so an empty one still shows the text input.
  it.each<['consumer' | 'classic', AssignorChoice, string, boolean]>([
    ['consumer', preset('uniform'), 'uniform', false],
    ['consumer', preset('range'), 'range', false],
    ['consumer', preset('foo'), CUSTOM_OPTION, true],
    ['consumer', preset(''), '', false],
    ['consumer', customAssignor(''), CUSTOM_OPTION, true],
    ['consumer', customAssignor('uniform'), CUSTOM_OPTION, true],
    ['classic', preset('range'), 'range', false],
    ['classic', preset('sticky'), 'sticky', false],
    ['classic', preset('foo'), 'foo', false],
  ])('%s shows %o as %j (custom: %s)', (protocol, choice, value, custom) => {
    expect(assignorOptionValue(META, protocol, choice)).toBe(value);
    expect(isCustomChoice(META, protocol, choice)).toBe(custom);
  });
});

describe('pickAssignorOption', () => {
  // Parity with `setGroupAssignor(value)` of simulator.html on initial-setup:
  // picking a preset selects it; picking custom starts an empty name when
  // leaving a preset and keeps a custom name already there.
  it.each<[AssignorChoice, string, AssignorChoice]>([
    [preset('uniform'), 'range', preset('range')],
    [preset('uniform'), '', preset('')],
    [preset('uniform'), CUSTOM_OPTION, customAssignor('')],
    [preset(''), CUSTOM_OPTION, customAssignor('')],
    [preset('sticky'), CUSTOM_OPTION, customAssignor('sticky')],
    [customAssignor('my-assignor'), CUSTOM_OPTION, customAssignor('my-assignor')],
    [customAssignor('my-assignor'), 'range', preset('range')],
  ])('from %o, picking %j gives %o', (current, option, expected) => {
    expect(pickAssignorOption(META, 'consumer', current, option)).toEqual(expected);
  });
});

describe('adaptAssignor', () => {
  // Parity with `setGroupProtocol(value)` of simulator.html on initial-setup:
  // an assignor that the new protocol accepts stays; another falls back. The
  // fallback is the default of the protocol (the prototype took the first
  // preset, `range`).
  it.each<['consumer' | 'classic', AssignorChoice, AssignorChoice]>([
    ['classic', preset('uniform'), DEFAULT_CHOICE],
    ['classic', customAssignor('mine'), DEFAULT_CHOICE],
    ['classic', preset('range'), preset('range')],
    ['classic', customAssignor('sticky'), preset('sticky')],
    ['classic', customAssignor(''), preset('')],
    ['consumer', preset('sticky'), preset('sticky')],
    ['consumer', customAssignor('mine'), customAssignor('mine')],
  ])('under %s, %o becomes %o', (protocol, choice, expected) => {
    expect(adaptAssignor(META, protocol, choice)).toEqual(expected);
  });

  it('returns the same choice when it is kept', () => {
    const choice = preset('range');
    expect(adaptAssignor(META, 'classic', choice)).toBe(choice);
  });
});

describe('assignorChoice', () => {
  it.each<['consumer' | 'classic', string, AssignorChoice]>([
    ['consumer', '', preset('')],
    ['consumer', 'uniform', preset('uniform')],
    ['consumer', 'mine', preset('mine')],
    ['classic', 'sticky', preset('sticky')],
    ['classic', 'mine', DEFAULT_CHOICE],
  ])('shows the %s assignor %j as %o', (protocol, name, expected) => {
    expect(assignorChoice(META, protocol, name)).toEqual(expected);
  });

  it('shows a name outside the presets of the consumer protocol as custom', () => {
    expect(assignorOptionValue(META, 'consumer', assignorChoice(META, 'consumer', 'mine'))).toBe(CUSTOM_OPTION);
  });
});

describe('assignorLabel', () => {
  it.each([
    ['', 'default'],
    ['range', 'range'],
  ])('labels %j as %j', (name, label) => {
    expect(assignorLabel(name)).toBe(label);
  });
});
