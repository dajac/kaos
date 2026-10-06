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

import type { components } from '../api/schema';

// The assignor picker. The presets of a protocol are its names in `assignors`
// of `/api/v1/meta`. A protocol that accepts a custom name (the consumer
// protocol) also offers "custom", which reveals a free-text name: on that
// protocol, any name outside the presets, the empty one included, is a
// custom name.

type Meta = components['schemas']['Meta'];
type Protocol = components['schemas']['Protocol'];

/**
 * The value of the "custom" option of the picker. It is not a legal name,
 * so it never collides with a preset or a custom name.
 */
export const CUSTOM_OPTION = '<custom>';

export interface AssignorOption {
  readonly value: string;
  readonly label: string;
}

/** Whether `protocol` accepts a name outside its presets. */
export function isCustomAllowed(meta: Meta, protocol: Protocol): boolean {
  return meta.assignors[protocol].customAllowed;
}

/** Whether `name` is a preset of `protocol`. */
export function isPresetAssignor(meta: Meta, protocol: Protocol, name: string): boolean {
  return meta.assignors[protocol].names.includes(name);
}

/** Whether `name` can be the assignor of a consumer of `protocol`. */
export function isValidAssignor(meta: Meta, protocol: Protocol, name: string): boolean {
  return isPresetAssignor(meta, protocol, name) || isCustomAllowed(meta, protocol);
}

/** The first preset of `protocol`: the assignor of a new group, and the fallback of a protocol switch. */
export function firstPreset(meta: Meta, protocol: Protocol): string {
  return meta.assignors[protocol].names[0] ?? '';
}

/** The options of the picker for `protocol`, in menu order. */
export function assignorOptions(meta: Meta, protocol: Protocol): AssignorOption[] {
  const options = meta.assignors[protocol].names.map((name) => ({ value: name, label: name }));
  if (isCustomAllowed(meta, protocol)) {
    options.push({ value: CUSTOM_OPTION, label: 'custom' });
  }
  return options;
}

/** Whether the picker shows `name` as a custom name, with its text input. */
export function isCustomAssignor(meta: Meta, protocol: Protocol, name: string): boolean {
  return isCustomAllowed(meta, protocol) && !isPresetAssignor(meta, protocol, name);
}

/**
 * The assignor after picking `option`. Picking "custom" starts an empty name
 * when leaving a preset, and keeps a custom name already there.
 */
export function pickAssignorOption(meta: Meta, protocol: Protocol, current: string, option: string): string {
  if (option !== CUSTOM_OPTION) {
    return option;
  }
  return isPresetAssignor(meta, protocol, current) ? '' : current;
}

/** The assignor that `name` becomes under `protocol`: unchanged when valid, the first preset otherwise. */
export function adaptAssignor(meta: Meta, protocol: Protocol, name: string): string {
  return isValidAssignor(meta, protocol, name) ? name : firstPreset(meta, protocol);
}
