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
import { GROUP_SETTING_KEYS, OVERRIDE_KEYS, type ConfigForm, type GroupSettingKey, type OverrideKey } from './configModel';

// The fields of the config panel that show an error, and the mapping of the
// violations of a refused start (`field` is a path of the start request, as
// `groups[0].consumers[1].overrides.pollDelayMs`) to these fields. A
// violation that names no field of the panel is left for the banner.

type Violation = components['schemas']['Violation'];

/** A field of a group that can show an error. */
export type GroupFieldName =
  | 'id'
  | 'numTopics'
  | 'partitions'
  | 'protocol'
  | 'assignor'
  | 'staticMember'
  | 'consumers'
  | GroupSettingKey;

/** The key of a field of the group at `groupIndex`. */
export function groupFieldKey(groupIndex: number, name: GroupFieldName): string {
  return `groups[${groupIndex}].${name}`;
}

/** The key of the override `key` of the consumer whose form key is `consumerKey`. */
export function overrideFieldKey(groupIndex: number, consumerKey: number, key: OverrideKey): string {
  return `groups[${groupIndex}].consumer#${consumerKey}.${key}`;
}

/** The errors of the panel: a message per field key, and the violations that name no field. */
export interface MappedViolations {
  readonly fields: Readonly<Record<string, string>>;
  readonly unmapped: readonly Violation[];
}

const GROUP_PATH = /^groups\[(\d+)\]\.(.+)$/;
const TOPIC_PATH = /^topics\[(\d+)\]\.(name|partitions)$/;
const CONSUMER_PATH = /^consumers\[(\d+)\]\.overrides\.(\w+)$/;

// The group whose topics hold the topic at `topicIndex` of the request, which
// lists the topics of every group in order.
function groupOfTopic(config: ConfigForm, topicIndex: number): number | undefined {
  let end = 0;
  for (const [index, group] of config.groups.entries()) {
    end += group.numTopics;
    if (topicIndex < end) {
      return index;
    }
  }
  return undefined;
}

function isGroupSetting(name: string): name is GroupSettingKey {
  return (GROUP_SETTING_KEYS as readonly string[]).includes(name);
}

function isOverrideKey(name: string): name is OverrideKey {
  return (OVERRIDE_KEYS as readonly string[]).includes(name);
}

// The field of the group that `rest`, a path inside `groups[index]`, names.
function groupField(config: ConfigForm, groupIndex: number, rest: string): string | undefined {
  const group = config.groups[groupIndex];
  if (group === undefined) {
    return undefined;
  }
  if (rest === 'id') {
    return groupFieldKey(groupIndex, 'id');
  }
  if (rest === 'subscribedTopics' || rest.startsWith('subscribedTopics[')) {
    return groupFieldKey(groupIndex, 'numTopics');
  }
  if (rest === 'consumers') {
    return groupFieldKey(groupIndex, 'consumers');
  }
  const setting = rest.startsWith('consumerDefaults.') ? rest.slice('consumerDefaults.'.length) : rest;
  if (setting === 'protocol' || setting === 'assignor' || setting === 'staticMember') {
    return rest === setting ? undefined : groupFieldKey(groupIndex, setting);
  }
  if (isGroupSetting(setting) && group.settings[setting] !== undefined) {
    return groupFieldKey(groupIndex, setting);
  }
  const consumer = CONSUMER_PATH.exec(rest);
  if (consumer !== null) {
    const form = group.consumers[Number(consumer[1])];
    const key = consumer[2] ?? '';
    if (form !== undefined && isOverrideKey(key) && form.overrides[key] !== undefined) {
      return overrideFieldKey(groupIndex, form.key, key);
    }
  }
  return undefined;
}

/** The field of the panel that the violation path `path` names, if any. */
export function violationField(config: ConfigForm, path: string): string | undefined {
  const group = GROUP_PATH.exec(path);
  if (group !== null) {
    return groupField(config, Number(group[1]), group[2] ?? '');
  }
  const topic = TOPIC_PATH.exec(path);
  if (topic !== null) {
    const groupIndex = groupOfTopic(config, Number(topic[1]));
    if (groupIndex === undefined) {
      return undefined;
    }
    return groupFieldKey(groupIndex, topic[2] === 'name' ? 'id' : 'partitions');
  }
  if (path === 'topics' && config.groups.length === 1) {
    return groupFieldKey(0, 'numTopics');
  }
  return undefined;
}

/**
 * The violations of a refused start, by field of `config`, the form that
 * built the request. A field shows the first violation that names it.
 */
export function mapViolations(config: ConfigForm, violations: readonly Violation[]): MappedViolations {
  const fields: Record<string, string> = {};
  const unmapped: Violation[] = [];
  for (const violation of violations) {
    const field = violationField(config, violation.field);
    if (field === undefined) {
      unmapped.push(violation);
    } else if (fields[field] === undefined) {
      fields[field] = violation.message;
    }
  }
  return { fields, unmapped };
}
