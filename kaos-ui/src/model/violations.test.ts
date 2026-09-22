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
  addGroupSetting,
  addOverride,
  initialConfig,
  newGroup,
  removeConsumer,
  setNumTopics,
  updateGroup,
  type ConfigForm,
} from './configModel';
import { META } from './meta.fixture';
import { groupFieldKey, mapViolations, overrideFieldKey, violationField } from './violations';

function config(): ConfigForm {
  let form = initialConfig(META);
  form = updateGroup(form, 0, (g) => addGroupSetting(META, g, 'sessionTimeoutMs'));
  form = updateGroup(form, 0, (g) => addGroupSetting(META, g, 'pollDelayMs'));
  // Consumer keys 0..3; after removing key 1, the request index 1 is key 2.
  form = updateGroup(form, 0, (g) => removeConsumer(g, 1));
  form = updateGroup(form, 0, (g) => addOverride(META, g, 2, 'maxPollIntervalMs'));
  return form;
}

describe('violationField', () => {
  it('maps the fields of the group', () => {
    const form = config();

    expect(violationField(form, 'groups[0].id')).toBe(groupFieldKey(0, 'id'));
    expect(violationField(form, 'groups[0].subscribedTopics')).toBe(groupFieldKey(0, 'numTopics'));
    expect(violationField(form, 'groups[0].subscribedTopics[2]')).toBe(groupFieldKey(0, 'numTopics'));
    expect(violationField(form, 'groups[0].consumers')).toBe(groupFieldKey(0, 'consumers'));
    expect(violationField(form, 'groups[0].consumerDefaults.assignor')).toBe(groupFieldKey(0, 'assignor'));
    expect(violationField(form, 'groups[0].consumerDefaults.protocol')).toBe(groupFieldKey(0, 'protocol'));
  });

  it('maps an optional setting only when it was added', () => {
    const form = config();

    expect(violationField(form, 'groups[0].sessionTimeoutMs')).toBe(groupFieldKey(0, 'sessionTimeoutMs'));
    expect(violationField(form, 'groups[0].consumerDefaults.pollDelayMs')).toBe(groupFieldKey(0, 'pollDelayMs'));
    expect(violationField(form, 'groups[0].heartbeatIntervalMs')).toBeUndefined();
    expect(violationField(form, 'groups[0].consumerDefaults.pollTimeoutMs')).toBeUndefined();
  });

  it('maps an override of a consumer by its position in the request', () => {
    const form = config();

    expect(violationField(form, 'groups[0].consumers[1].overrides.maxPollIntervalMs')).toBe(
      overrideFieldKey(0, 2, 'maxPollIntervalMs'),
    );
    expect(violationField(form, 'groups[0].consumers[0].overrides.maxPollIntervalMs')).toBeUndefined();
    expect(violationField(form, 'groups[0].consumers[9].overrides.maxPollIntervalMs')).toBeUndefined();
    expect(violationField(form, 'groups[0].consumers[1].id')).toBeUndefined();
  });

  it('maps the topics to the group that names them', () => {
    let form = config();
    form = updateGroup(form, 0, (g) => setNumTopics(g, 2));
    form = { ...form, groups: [...form.groups, newGroup(META, 'other')] };

    expect(violationField(form, 'topics[1].name')).toBe(groupFieldKey(0, 'id'));
    expect(violationField(form, 'topics[1].partitions')).toBe(groupFieldKey(0, 'partitions'));
    expect(violationField(form, 'topics[2].name')).toBe(groupFieldKey(1, 'id'));
    expect(violationField(form, 'topics[3].name')).toBeUndefined();
    expect(violationField(form, 'topics')).toBeUndefined();
  });

  it('maps the topic limit to the number of topics of a single group', () => {
    expect(violationField(config(), 'topics')).toBe(groupFieldKey(0, 'numTopics'));
  });

  it('leaves the other paths unmapped', () => {
    const form = config();

    expect(violationField(form, 'producers[0].ratePerPartition')).toBeUndefined();
    expect(violationField(form, 'groups[3].id')).toBeUndefined();
    expect(violationField(form, 'groups[0].consumerDefaults.rackId')).toBeUndefined();
  });
});

describe('mapViolations', () => {
  it('keeps the first message of a field and the violations that name no field', () => {
    const producer = { field: 'producers[0].ratePerPartition', message: 'must be positive' };

    const mapped = mapViolations(config(), [
      { field: 'groups[0].id', message: 'must not be blank' },
      { field: 'topics[0].name', message: 'must match the name rule' },
      producer,
    ]);

    expect(mapped.fields).toEqual({ [groupFieldKey(0, 'id')]: 'must not be blank' });
    expect(mapped.unmapped).toEqual([producer]);
  });
});
