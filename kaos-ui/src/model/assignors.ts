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

// The assignor picker. Its presets per protocol come from `assignors` of
// `/api/v1/meta`, after the empty name, which selects the default of the
// protocol. A protocol that accepts a custom name (the consumer protocol)
// also offers "custom", which reveals a free-text name.

type Meta = components['schemas']['Meta'];
type Protocol = components['schemas']['Protocol'];

/** The empty assignor name: the default of the protocol. */
export const DEFAULT_ASSIGNOR = '';

/**
 * The value of the "custom" option of the picker. It is not a legal name,
 * so it never collides with a preset or a custom name.
 */
export const CUSTOM_OPTION = '<custom>';

/**
 * An assignor as the form holds it: the name that is sent, and whether the
 * picker shows it as a custom name. A custom name may be empty while it is
 * typed, and is then sent as the default of the protocol.
 */
export interface AssignorChoice {
  readonly name: string;
  readonly custom: boolean;
}

export interface AssignorOption {
  readonly value: string;
  readonly label: string;
}

/** The choice of the default of the protocol. */
export const DEFAULT_CHOICE: AssignorChoice = { name: DEFAULT_ASSIGNOR, custom: false };

/** Whether `protocol` accepts a name outside its presets. */
export function isCustomAllowed(meta: Meta, protocol: Protocol): boolean {
  return meta.assignors[protocol].customAllowed;
}

/** Whether `name` is a preset of `protocol`, the default included. */
export function isPresetAssignor(meta: Meta, protocol: Protocol, name: string): boolean {
  return name === DEFAULT_ASSIGNOR || meta.assignors[protocol].names.includes(name);
}

/** Whether `name` can be the assignor of a consumer of `protocol`. */
export function isValidAssignor(meta: Meta, protocol: Protocol, name: string): boolean {
  return isPresetAssignor(meta, protocol, name) || isCustomAllowed(meta, protocol);
}

/** The label of an assignor name: "default" for the empty name. */
export function assignorLabel(name: string): string {
  return name === DEFAULT_ASSIGNOR ? 'default' : name;
}

/** The options of the picker for `protocol`, in menu order. */
export function assignorOptions(meta: Meta, protocol: Protocol): AssignorOption[] {
  const options = [DEFAULT_ASSIGNOR, ...meta.assignors[protocol].names].map((name) => ({
    value: name,
    label: assignorLabel(name),
  }));
  if (isCustomAllowed(meta, protocol)) {
    options.push({ value: CUSTOM_OPTION, label: 'custom' });
  }
  return options;
}

/** Whether the picker shows `choice` as a custom name, with its text input. */
export function isCustomChoice(meta: Meta, protocol: Protocol, choice: AssignorChoice): boolean {
  return isCustomAllowed(meta, protocol) && (choice.custom || !isPresetAssignor(meta, protocol, choice.name));
}

/** The selected option of the picker for `choice`. */
export function assignorOptionValue(meta: Meta, protocol: Protocol, choice: AssignorChoice): string {
  return isCustomChoice(meta, protocol, choice) ? CUSTOM_OPTION : choice.name;
}

/**
 * The choice after picking `option`. Picking "custom" keeps a custom name
 * already typed and starts an empty one otherwise.
 */
export function pickAssignorOption(
  meta: Meta,
  protocol: Protocol,
  current: AssignorChoice,
  option: string,
): AssignorChoice {
  if (option !== CUSTOM_OPTION) {
    return { name: option, custom: false };
  }
  return isCustomChoice(meta, protocol, current) ? { name: current.name, custom: true } : { name: '', custom: true };
}

/** The choice of a custom name typed in the text input. */
export function customAssignor(name: string): AssignorChoice {
  return { name, custom: true };
}

/** The choice that shows the assignor `name` of a spec. */
export function assignorChoice(meta: Meta, protocol: Protocol, name: string): AssignorChoice {
  return adaptAssignor(meta, protocol, { name, custom: false });
}

/**
 * The choice that `choice` becomes under `protocol`: unchanged when the
 * protocol accepts it, the default of the protocol otherwise. A name that is
 * a preset of `protocol` is shown as a preset when custom names are not
 * accepted.
 */
export function adaptAssignor(meta: Meta, protocol: Protocol, choice: AssignorChoice): AssignorChoice {
  if (isCustomAllowed(meta, protocol)) {
    return choice;
  }
  if (isPresetAssignor(meta, protocol, choice.name)) {
    return choice.custom ? { name: choice.name, custom: false } : choice;
  }
  return DEFAULT_CHOICE;
}
