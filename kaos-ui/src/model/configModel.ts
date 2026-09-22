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
import {
  adaptAssignor,
  assignorChoice,
  assignorLabel,
  customAssignor,
  pickAssignorOption,
  type AssignorChoice,
} from './assignors';

// The state of the config panel and the functions that change it. Every
// function is pure: it returns a new state and leaves its arguments alone.
//
// The config holds `groups[]`. Each group has its topics, `<groupId>-1..N`,
// its settings and its consumers; one producer writes to the topics of every
// group. A consumer holds only its overrides and inherits everything else
// from its group. The defaults, the labels and the ranges come from
// `/api/v1/meta`.

type Meta = components['schemas']['Meta'];
type ConfigField = components['schemas']['ConfigField'];
type Protocol = components['schemas']['Protocol'];
type Simulator = components['schemas']['Simulator'];
type SimulatorRequest = components['schemas']['SimulatorRequest'];
type GroupRequest = components['schemas']['GroupRequest'];
type ConsumerOverrides = components['schemas']['ConsumerOverrides'];
type ConsumerSettings = components['schemas']['ConsumerSettings'];

export type Scope = ConfigField['scope'];

/** The settings of a consumer as the form holds them. */
export interface ConsumerSettingsForm {
  readonly protocol: Protocol;
  readonly assignor: AssignorChoice;
  readonly staticMember: boolean;
  readonly rackId: string;
  readonly pollTimeoutMs: number;
  readonly pollDelayMs: number;
  readonly maxPollIntervalMs: number;
}

export type OverrideKey = keyof ConsumerSettingsForm;

/** The overrides of a consumer, in the order they were added. */
export type Overrides = Partial<ConsumerSettingsForm>;

/** The overridable settings, in the order of the "+ Add override" menu and of the chips. */
export const OVERRIDE_KEYS: readonly OverrideKey[] = [
  'protocol',
  'assignor',
  'staticMember',
  'rackId',
  'pollTimeoutMs',
  'pollDelayMs',
  'maxPollIntervalMs',
];

/** How the form edits an override. */
export type OverrideKind = 'protocol' | 'assignor' | 'boolean' | 'text' | 'number';

export const OVERRIDE_KINDS: Readonly<Record<OverrideKey, OverrideKind>> = {
  protocol: 'protocol',
  assignor: 'assignor',
  staticMember: 'boolean',
  rackId: 'text',
  pollTimeoutMs: 'number',
  pollDelayMs: 'number',
  maxPollIntervalMs: 'number',
};

/** The short names of the settings on the chips of a collapsed consumer. */
export const CHIP_LABELS: Readonly<Record<OverrideKey, string>> = {
  protocol: 'protocol',
  assignor: 'assignor',
  staticMember: 'static',
  rackId: 'rack',
  pollTimeoutMs: 'pollTimeout',
  pollDelayMs: 'pollDelay',
  maxPollIntervalMs: 'maxPoll',
};

/** The optional settings of a group, added with "+ Add setting", in menu order. */
export type GroupSettingKey =
  | 'sessionTimeoutMs'
  | 'heartbeatIntervalMs'
  | 'assignmentIntervalMs'
  | 'pollTimeoutMs'
  | 'pollDelayMs'
  | 'maxPollIntervalMs';

export const GROUP_SETTING_KEYS: readonly GroupSettingKey[] = [
  'sessionTimeoutMs',
  'heartbeatIntervalMs',
  'assignmentIntervalMs',
  'pollTimeoutMs',
  'pollDelayMs',
  'maxPollIntervalMs',
];

const GROUP_SETTING_SCOPES: Readonly<Record<GroupSettingKey, Scope>> = {
  sessionTimeoutMs: 'GROUP',
  heartbeatIntervalMs: 'GROUP',
  assignmentIntervalMs: 'GROUP',
  pollTimeoutMs: 'CONSUMER',
  pollDelayMs: 'CONSUMER',
  maxPollIntervalMs: 'CONSUMER',
};

export interface ConsumerForm {
  /** A key that is stable while the form lives, for the list of consumers. */
  readonly key: number;
  readonly expanded: boolean;
  readonly overrides: Overrides;
}

export interface GroupForm {
  readonly id: string;
  readonly numTopics: number;
  readonly partitions: number;
  readonly protocol: Protocol;
  readonly assignor: AssignorChoice;
  readonly staticMember: boolean;
  /** The rack id the consumers inherit. The panel does not edit it; a rehydrated spec keeps it. */
  readonly rackId: string;
  /** The optional settings that were added, in the order they were added. */
  readonly settings: Readonly<Partial<Record<GroupSettingKey, number>>>;
  readonly consumers: readonly ConsumerForm[];
  readonly nextConsumerKey: number;
}

export interface ConfigForm {
  /** The rate of the producer. The panel does not edit it; a rehydrated spec keeps it. */
  readonly ratePerPartition: number;
  readonly groups: readonly GroupForm[];
}

/** A field of the form, with its label, its default and, for a bounded integer, its range. */
export interface FieldInfo<K extends string = string> {
  readonly key: K;
  readonly label: string;
  readonly defaultValue: number | boolean | string;
  readonly min?: number;
  readonly max?: number;
}

/** An entry of an "+ Add …" menu: the setting and the value it starts from. */
export interface AddOption<K extends string> {
  readonly key: K;
  readonly label: string;
  readonly hint: string;
}

export interface Chip {
  readonly key: OverrideKey;
  readonly label: string;
  readonly value: string;
}

// -- Metadata --------------------------------------------------------------

/** The field `key` of `scope` in `/api/v1/meta`. */
export function metaField(meta: Meta, scope: Scope, key: string): FieldInfo {
  const field = meta.fields.find((f) => f.scope === scope && f.key === key);
  if (field === undefined) {
    throw new Error(`/api/v1/meta has no ${scope} field ${key}`);
  }
  return {
    key: field.key,
    label: field.label,
    defaultValue: field.defaultValue,
    ...(field.range === undefined ? {} : { min: field.range.min, max: field.range.max }),
  };
}

function intDefault(meta: Meta, scope: Scope, key: string): number {
  return Number(metaField(meta, scope, key).defaultValue);
}

function stringDefault(meta: Meta, scope: Scope, key: string): string {
  return String(metaField(meta, scope, key).defaultValue);
}

/** The field of an optional group setting. */
export function groupSettingField(meta: Meta, key: GroupSettingKey): FieldInfo<GroupSettingKey> {
  return { ...metaField(meta, GROUP_SETTING_SCOPES[key], key), key };
}

/** The field of an override. */
export function overrideField(meta: Meta, key: OverrideKey): FieldInfo<OverrideKey> {
  return { ...metaField(meta, 'CONSUMER', key), key };
}

/** The integer that a number input holds: `parseInt`, and 0 for text that is not a number. */
export function parseIntInput(text: string): number {
  const value = Number.parseInt(text, 10);
  return Number.isNaN(value) ? 0 : value;
}

// -- Creation --------------------------------------------------------------

/** A group with the defaults of `/api/v1/meta` and `numConsumers` consumers that inherit everything. */
export function newGroup(meta: Meta, id: string = stringDefault(meta, 'GROUP', 'id')): GroupForm {
  let group: GroupForm = {
    id,
    numTopics: intDefault(meta, 'SIMULATOR', 'numTopics'),
    partitions: intDefault(meta, 'TOPIC', 'partitions'),
    protocol: stringDefault(meta, 'CONSUMER', 'protocol') as Protocol,
    assignor: { name: stringDefault(meta, 'CONSUMER', 'assignor'), custom: false },
    staticMember: Boolean(metaField(meta, 'CONSUMER', 'staticMember').defaultValue),
    rackId: stringDefault(meta, 'CONSUMER', 'rackId'),
    settings: {},
    consumers: [],
    nextConsumerKey: 0,
  };
  const numConsumers = intDefault(meta, 'GROUP', 'numConsumers');
  for (let i = 0; i < numConsumers; i++) {
    group = addConsumer(group);
  }
  return group;
}

/** The config of a fresh panel: one group with the defaults. */
export function initialConfig(meta: Meta): ConfigForm {
  return {
    ratePerPartition: Number(metaField(meta, 'PRODUCER', 'ratePerPartition').defaultValue),
    groups: [newGroup(meta)],
  };
}

/** `config` with the group at `index` replaced by `update(group)`. */
export function updateGroup(config: ConfigForm, index: number, update: (group: GroupForm) => GroupForm): ConfigForm {
  return { ...config, groups: config.groups.map((group, i) => (i === index ? update(group) : group)) };
}

// -- Group fields ----------------------------------------------------------

export function setGroupId(group: GroupForm, id: string): GroupForm {
  return { ...group, id };
}

export function setNumTopics(group: GroupForm, numTopics: number): GroupForm {
  return { ...group, numTopics };
}

export function setPartitions(group: GroupForm, partitions: number): GroupForm {
  return { ...group, partitions };
}

/** The group with `protocol`; its assignor falls back to the default when the protocol does not accept it. */
export function setGroupProtocol(meta: Meta, group: GroupForm, protocol: Protocol): GroupForm {
  return { ...group, protocol, assignor: adaptAssignor(meta, protocol, group.assignor) };
}

/** The group after picking `option` in its assignor picker. */
export function pickGroupAssignor(meta: Meta, group: GroupForm, option: string): GroupForm {
  return { ...group, assignor: pickAssignorOption(meta, group.protocol, group.assignor, option) };
}

/** The group with the custom assignor `name`, typed in the text input. */
export function setGroupCustomAssignor(group: GroupForm, name: string): GroupForm {
  return { ...group, assignor: customAssignor(name) };
}

export function setGroupStatic(group: GroupForm, staticMember: boolean): GroupForm {
  return { ...group, staticMember };
}

/** The topics of the group: `<groupId>-1..N`. */
export function topicNames(group: GroupForm): string[] {
  return Array.from({ length: group.numTopics }, (_, i) => `${group.id}-${i + 1}`);
}

// -- Optional group settings -----------------------------------------------

/** The group with the setting `key` added, at its default. */
export function addGroupSetting(meta: Meta, group: GroupForm, key: GroupSettingKey): GroupForm {
  return { ...group, settings: { ...group.settings, [key]: intDefault(meta, GROUP_SETTING_SCOPES[key], key) } };
}

export function setGroupSetting(group: GroupForm, key: GroupSettingKey, value: number): GroupForm {
  return { ...group, settings: { ...group.settings, [key]: value } };
}

/** The group without the setting `key`, which reverts to its default. */
export function removeGroupSetting(group: GroupForm, key: GroupSettingKey): GroupForm {
  const { [key]: _removed, ...settings } = group.settings;
  return { ...group, settings };
}

/** The settings that were added, in the order they were added. */
export function groupSettingKeys(group: GroupForm): GroupSettingKey[] {
  return Object.keys(group.settings) as GroupSettingKey[];
}

/** The entries of the "+ Add setting" menu: the settings not added yet, with their defaults. */
export function addGroupSettingOptions(meta: Meta, group: GroupForm): AddOption<GroupSettingKey>[] {
  return GROUP_SETTING_KEYS.filter((key) => group.settings[key] === undefined).map((key) => {
    const field = groupSettingField(meta, key);
    return { key, label: field.label, hint: String(field.defaultValue) };
  });
}

// -- Inheritance -----------------------------------------------------------

/** The settings the consumers of the group inherit. */
export function consumerDefaults(meta: Meta, group: GroupForm): ConsumerSettingsForm {
  const setting = (key: 'pollTimeoutMs' | 'pollDelayMs' | 'maxPollIntervalMs') =>
    group.settings[key] ?? intDefault(meta, 'CONSUMER', key);
  return {
    protocol: group.protocol,
    assignor: group.assignor,
    staticMember: group.staticMember,
    rackId: group.rackId,
    pollTimeoutMs: setting('pollTimeoutMs'),
    pollDelayMs: setting('pollDelayMs'),
    maxPollIntervalMs: setting('maxPollIntervalMs'),
  };
}

/** The coordination settings of the group. */
export function coordinationSettings(
  meta: Meta,
  group: GroupForm,
): Pick<GroupRequest, 'sessionTimeoutMs' | 'heartbeatIntervalMs' | 'assignmentIntervalMs'> {
  const setting = (key: 'sessionTimeoutMs' | 'heartbeatIntervalMs' | 'assignmentIntervalMs') =>
    group.settings[key] ?? intDefault(meta, 'GROUP', key);
  return {
    sessionTimeoutMs: setting('sessionTimeoutMs'),
    heartbeatIntervalMs: setting('heartbeatIntervalMs'),
    assignmentIntervalMs: setting('assignmentIntervalMs'),
  };
}

/** The settings of `consumer`: its overrides, and the settings of its group for the rest. */
export function effectiveSettings(meta: Meta, group: GroupForm, consumer: ConsumerForm): ConsumerSettingsForm {
  return { ...consumerDefaults(meta, group), ...consumer.overrides };
}

// -- Consumers -------------------------------------------------------------

/** The name of the consumer at `index`: the id the group assigns to it, `consumer-<index>`. */
export function consumerName(index: number): string {
  return `consumer-${index}`;
}

function withConsumer(group: GroupForm, key: number, update: (consumer: ConsumerForm) => ConsumerForm): GroupForm {
  return { ...group, consumers: group.consumers.map((c) => (c.key === key ? update(c) : c)) };
}

function appendConsumer(group: GroupForm, overrides: Overrides): GroupForm {
  const consumer: ConsumerForm = { key: group.nextConsumerKey, expanded: false, overrides };
  return { ...group, consumers: [...group.consumers, consumer], nextConsumerKey: group.nextConsumerKey + 1 };
}

/** The group with a new, collapsed consumer that inherits everything. */
export function addConsumer(group: GroupForm): GroupForm {
  return appendConsumer(group, {});
}

/** The group with a copy of the overrides of its last consumer, or a new consumer when it has none. */
export function duplicateLastConsumer(group: GroupForm): GroupForm {
  const last = group.consumers.at(-1);
  return last === undefined ? addConsumer(group) : appendConsumer(group, { ...last.overrides });
}

export function removeConsumer(group: GroupForm, key: number): GroupForm {
  return { ...group, consumers: group.consumers.filter((c) => c.key !== key) };
}

export function toggleConsumer(group: GroupForm, key: number): GroupForm {
  return withConsumer(group, key, (c) => ({ ...c, expanded: !c.expanded }));
}

// -- Overrides -------------------------------------------------------------

// After a change of the protocol override, the assignor that the consumer
// ends up with must suit the protocol: an assignor override is adapted, and
// an inherited assignor that does not suit it is overridden by the default.
function adaptOverriddenProtocol(meta: Meta, group: GroupForm, overrides: Overrides): Overrides {
  const effective = { ...consumerDefaults(meta, group), ...overrides };
  const adapted = adaptAssignor(meta, effective.protocol, effective.assignor);
  if (overrides.assignor !== undefined || adapted.name !== effective.assignor.name) {
    return { ...overrides, assignor: adapted };
  }
  return overrides;
}

/** The group with the override `key` added to the consumer `key`, at the value it inherits. */
export function addOverride(meta: Meta, group: GroupForm, consumerKey: number, key: OverrideKey): GroupForm {
  return withConsumer(group, consumerKey, (consumer) => {
    const overrides = { ...consumer.overrides, [key]: effectiveSettings(meta, group, consumer)[key] };
    return { ...consumer, overrides: key === 'protocol' ? adaptOverriddenProtocol(meta, group, overrides) : overrides };
  });
}

/** The group with the override `key` of the consumer `consumerKey` set to `value`. */
export function setOverride<K extends OverrideKey>(
  meta: Meta,
  group: GroupForm,
  consumerKey: number,
  key: K,
  value: ConsumerSettingsForm[K],
): GroupForm {
  return withConsumer(group, consumerKey, (consumer) => {
    const overrides = { ...consumer.overrides, [key]: value };
    return { ...consumer, overrides: key === 'protocol' ? adaptOverriddenProtocol(meta, group, overrides) : overrides };
  });
}

/** The group after picking `option` in the assignor picker of the consumer `consumerKey`. */
export function pickOverrideAssignor(meta: Meta, group: GroupForm, consumerKey: number, option: string): GroupForm {
  const consumer = group.consumers.find((c) => c.key === consumerKey);
  if (consumer === undefined) {
    return group;
  }
  const effective = effectiveSettings(meta, group, consumer);
  return setOverride(
    meta,
    group,
    consumerKey,
    'assignor',
    pickAssignorOption(meta, effective.protocol, effective.assignor, option),
  );
}

/** The group without the override `key` of the consumer `consumerKey`, which inherits it again. */
export function removeOverride(group: GroupForm, consumerKey: number, key: OverrideKey): GroupForm {
  return withConsumer(group, consumerKey, (consumer) => {
    const { [key]: _removed, ...overrides } = consumer.overrides;
    return { ...consumer, overrides };
  });
}

/** The overrides of the consumer, in the order they were added. */
export function overrideKeys(consumer: ConsumerForm): OverrideKey[] {
  return Object.keys(consumer.overrides) as OverrideKey[];
}

/** Whether the consumer has no override, and so shows "Inherits all settings from group." */
export function inheritsAll(consumer: ConsumerForm): boolean {
  return overrideKeys(consumer).length === 0;
}

/** A setting as a chip or a hint shows it. */
export function formatSetting<K extends OverrideKey>(key: K, value: ConsumerSettingsForm[K]): string {
  if (key === 'staticMember') {
    return value ? 'on' : 'off';
  }
  if (key === 'assignor') {
    return assignorLabel((value as AssignorChoice).name);
  }
  return String(value);
}

/** The chips of a collapsed consumer: its overrides, in menu order. */
export function consumerChips(consumer: ConsumerForm): Chip[] {
  return OVERRIDE_KEYS.flatMap((key) => {
    const value = consumer.overrides[key];
    return value === undefined ? [] : [{ key, label: CHIP_LABELS[key], value: formatSetting(key, value) }];
  });
}

/** The entries of the "+ Add override" menu: the settings not overridden yet, with the values they inherit. */
export function addOverrideOptions(meta: Meta, group: GroupForm, consumer: ConsumerForm): AddOption<OverrideKey>[] {
  const effective = effectiveSettings(meta, group, consumer);
  return OVERRIDE_KEYS.filter((key) => consumer.overrides[key] === undefined).map((key) => ({
    key,
    label: overrideField(meta, key).label,
    hint: formatSetting(key, effective[key]),
  }));
}

// -- Start payload and rehydration -----------------------------------------

function toConsumerSettings(settings: ConsumerSettingsForm): ConsumerSettings {
  return { ...settings, assignor: settings.assignor.name };
}

function toConsumerOverrides(overrides: Overrides): ConsumerOverrides {
  const result: Record<string, unknown> = {};
  for (const key of OVERRIDE_KEYS) {
    const value = overrides[key];
    if (value !== undefined) {
      result[key] = key === 'assignor' ? (value as AssignorChoice).name : value;
    }
  }
  return result as ConsumerOverrides;
}

/**
 * The body of `POST /api/v1/simulator`: the topics of every group, one
 * producer that writes to all of them, and the groups. The consumers send
 * their overrides only, and the group assigns their ids, `consumer-0..N-1`.
 */
export function buildStartRequest(meta: Meta, config: ConfigForm): SimulatorRequest {
  const topics = config.groups.flatMap((group) =>
    topicNames(group).map((name) => ({ name, partitions: group.partitions })),
  );
  return {
    topics,
    producers:
      topics.length === 0 ? [] : [{ topics: topics.map((t) => t.name), ratePerPartition: config.ratePerPartition }],
    groups: config.groups.map((group) => ({
      id: group.id,
      subscribedTopics: topicNames(group),
      ...coordinationSettings(meta, group),
      consumerDefaults: toConsumerSettings(consumerDefaults(meta, group)),
      consumers: group.consumers.map((consumer) => ({ overrides: toConsumerOverrides(consumer.overrides) })),
    })),
  };
}

function fromConsumerOverrides(meta: Meta, defaultProtocol: Protocol, overrides: ConsumerOverrides): Overrides {
  const result: Record<string, unknown> = {};
  for (const key of OVERRIDE_KEYS) {
    const value = overrides[key];
    if (value !== undefined) {
      result[key] =
        key === 'assignor' ? assignorChoice(meta, overrides.protocol ?? defaultProtocol, value as string) : value;
    }
  }
  return result as Overrides;
}

/**
 * The config that shows the spec of `GET /api/v1/simulator`, overrides
 * included: an optional setting is added when it differs from its default.
 * A spec without groups gives the initial config.
 */
export function configFromSimulator(meta: Meta, simulator: Simulator): ConfigForm {
  const { spec } = simulator;
  if (spec.groups.length === 0) {
    return initialConfig(meta);
  }
  const partitionsOf = new Map(spec.topics.map((t) => [t.name, t.partitions]));
  const groups = spec.groups.map((g): GroupForm => {
    const defaults = g.consumerDefaults;
    const values: Record<GroupSettingKey, number> = {
      sessionTimeoutMs: g.sessionTimeoutMs,
      heartbeatIntervalMs: g.heartbeatIntervalMs,
      assignmentIntervalMs: g.assignmentIntervalMs,
      pollTimeoutMs: defaults.pollTimeoutMs,
      pollDelayMs: defaults.pollDelayMs,
      maxPollIntervalMs: defaults.maxPollIntervalMs,
    };
    const settings: Partial<Record<GroupSettingKey, number>> = {};
    for (const key of GROUP_SETTING_KEYS) {
      if (values[key] !== intDefault(meta, GROUP_SETTING_SCOPES[key], key)) {
        settings[key] = values[key];
      }
    }
    const firstTopic = g.subscribedTopics[0];
    const partitions = firstTopic === undefined ? undefined : partitionsOf.get(firstTopic);
    return {
      id: g.id,
      numTopics: g.subscribedTopics.length,
      partitions: partitions ?? intDefault(meta, 'TOPIC', 'partitions'),
      protocol: defaults.protocol,
      assignor: assignorChoice(meta, defaults.protocol, defaults.assignor),
      staticMember: defaults.staticMember,
      rackId: defaults.rackId,
      settings,
      consumers: g.consumers.map((c, key) => ({
        key,
        expanded: false,
        overrides: fromConsumerOverrides(meta, defaults.protocol, c.overrides),
      })),
      nextConsumerKey: g.consumers.length,
    };
  });
  return {
    ratePerPartition:
      spec.producers[0]?.ratePerPartition ?? Number(metaField(meta, 'PRODUCER', 'ratePerPartition').defaultValue),
    groups,
  };
}
