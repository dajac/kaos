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

import type { components } from '../api/schema';
import { CUSTOM_OPTION } from './assignors';
import {
  addConsumer,
  addGroupSetting,
  addGroupSettingOptions,
  addOverride,
  addOverrideOptions,
  buildStartRequest,
  configFromSimulator,
  consumerChips,
  consumerDefaults,
  consumerName,
  coordinationSettings,
  duplicateLastConsumer,
  effectiveSettings,
  formatSetting,
  groupSettingField,
  groupSettingKeys,
  inheritsAll,
  initialConfig,
  metaField,
  newGroup,
  overrideField,
  overrideKeys,
  parseIntInput,
  pickGroupAssignor,
  pickOverrideAssignor,
  removeConsumer,
  removeGroupSetting,
  removeOverride,
  setGroupCustomAssignor,
  setGroupId,
  setGroupProtocol,
  setGroupSetting,
  setGroupStatic,
  setNumTopics,
  setOverride,
  setPartitions,
  startProblems,
  toggleConsumer,
  topicNames,
  updateGroup,
  type ConfigForm,
  type GroupForm,
} from './configModel';
import { META } from './meta.fixture';

type Simulator = components['schemas']['Simulator'];
type SimulatorSpec = components['schemas']['SimulatorSpec'];

// The prototype starts from a group without consumers in these scenarios.
const emptyGroup = (): GroupForm => ({ ...newGroup(META), consumers: [], nextConsumerKey: 0 });

const overridesOf = (group: GroupForm, index = 0) => group.consumers[index]?.overrides;

describe('metadata', () => {
  it('reads a field of /api/v1/meta, with its range', () => {
    expect(metaField(META, 'GROUP', 'sessionTimeoutMs')).toEqual({
      key: 'sessionTimeoutMs',
      label: 'Session Timeout (ms)',
      defaultValue: 45000,
      min: 1000,
      max: 300000,
    });
    expect(metaField(META, 'CONSUMER', 'rackId')).toEqual({ key: 'rackId', label: 'Rack ID', defaultValue: '' });
  });

  it('refuses a field that /api/v1/meta does not have', () => {
    expect(() => metaField(META, 'GROUP', 'bogus')).toThrow('/api/v1/meta has no GROUP field bogus');
  });

  // Parity with the labels and ranges of `GROUP_OPTIONAL_FIELDS` and
  // `OVERRIDE_FIELDS` of simulator.html on initial-setup.
  it.each([
    ['sessionTimeoutMs', 'Session Timeout (ms)', 45000, 1000, 300000],
    ['heartbeatIntervalMs', 'Heartbeat Interval (ms)', 5000, 500, 60000],
    ['assignmentIntervalMs', 'Assignment Interval (ms)', 1000, 0, 60000],
    ['pollTimeoutMs', 'Poll Timeout (ms)', 0, 0, 60000],
    ['pollDelayMs', 'Poll Delay (ms)', 1000, 0, 120000],
    ['maxPollIntervalMs', 'Max Poll Interval (ms)', 300000, 1000, 600000],
  ] as const)('describes the group setting %s', (key, label, defaultValue, min, max) => {
    expect(groupSettingField(META, key)).toEqual({ key, label, defaultValue, min, max });
  });

  it.each([
    ['protocol', 'Protocol'],
    ['assignor', 'Assignor'],
    ['staticMember', 'Static Membership'],
    ['rackId', 'Rack ID'],
    ['pollTimeoutMs', 'Poll Timeout (ms)'],
    ['pollDelayMs', 'Poll Delay (ms)'],
    ['maxPollIntervalMs', 'Max Poll Interval (ms)'],
  ] as const)('labels the override %s %j', (key, label) => {
    expect(overrideField(META, key).label).toBe(label);
  });

  // Parity with `setGroupSetting(key, value)` of simulator.html on
  // initial-setup: `parseInt`, and 0 for text that is not a number.
  it.each([
    ['250', 250],
    ['abc', 0],
    ['', 0],
    ['12abc', 12],
    ['-5', -5],
  ])('parses %j as %d', (text, value) => {
    expect(parseIntInput(text)).toBe(value);
  });
});

describe('initial config', () => {
  // Parity with the form of simulator.html on initial-setup (group id `kaos`,
  // 1 topic, 36 partitions) and with `getDefaults()` and
  // `getCoordinationDefaults()` before any change: the assignor `uniform`
  // and 3 consumers that inherit everything. The values come from
  // `/api/v1/meta`.
  it('has one group with the defaults of /api/v1/meta', () => {
    const config = initialConfig(META);

    expect(config.ratePerPartition).toBe(2);
    expect(config.groups).toHaveLength(1);
    const group = config.groups[0]!;
    expect(group).toMatchObject({
      id: 'kaos',
      numTopics: 1,
      partitions: 36,
      protocol: 'consumer',
      assignor: 'uniform',
      staticMember: false,
      rackId: '',
      settings: {},
      nextConsumerKey: 3,
    });
    expect(group.consumers).toEqual([0, 1, 2].map((key) => ({ key, expanded: false, overrides: {} })));
    expect(consumerDefaults(META, group)).toEqual({
      protocol: 'consumer',
      assignor: 'uniform',
      staticMember: false,
      rackId: '',
      pollTimeoutMs: 0,
      pollDelayMs: 1000,
      maxPollIntervalMs: 300000,
    });
    expect(coordinationSettings(META, group)).toEqual({
      sessionTimeoutMs: 45000,
      heartbeatIntervalMs: 5000,
      assignmentIntervalMs: 1000,
    });
  });

  it('names a new group after the given id', () => {
    expect(newGroup(META, 'orders').id).toBe('orders');
  });

  it('names the topics of a group <groupId>-1..N', () => {
    const group = setNumTopics(setGroupId(emptyGroup(), 'orders'), 3);
    expect(topicNames(group)).toEqual(['orders-1', 'orders-2', 'orders-3']);
    expect(topicNames(setNumTopics(group, 0))).toEqual([]);
  });

  it('sets the partitions and updates one group of the config', () => {
    const config: ConfigForm = { ratePerPartition: 2, groups: [newGroup(META, 'a'), newGroup(META, 'b')] };
    const updated = updateGroup(config, 1, (g) => setPartitions(g, 12));

    expect(updated.groups[0]).toBe(config.groups[0]);
    expect(updated.groups[1]?.partitions).toBe(12);
    expect(config.groups[1]?.partitions).toBe(36);
  });
});

describe('optional group settings', () => {
  // Parity with `addGroupSetting(key)`, `setGroupSetting(key, value)`,
  // `removeGroupSetting(key)`, `getDefaults()` and
  // `getCoordinationDefaults()` of simulator.html on initial-setup, run in
  // this sequence.
  it('adds, sets and removes settings in the order they were added', () => {
    let group = addGroupSetting(META, emptyGroup(), 'pollDelayMs');
    group = addGroupSetting(META, group, 'sessionTimeoutMs');
    expect(groupSettingKeys(group)).toEqual(['pollDelayMs', 'sessionTimeoutMs']);
    expect(group.settings).toEqual({ pollDelayMs: 1000, sessionTimeoutMs: 45000 });

    group = setGroupSetting(group, 'pollDelayMs', parseIntInput('250'));
    group = setGroupSetting(group, 'sessionTimeoutMs', parseIntInput('abc'));
    expect(group.settings).toEqual({ pollDelayMs: 250, sessionTimeoutMs: 0 });
    expect(consumerDefaults(META, group)).toEqual({
      protocol: 'consumer',
      assignor: 'uniform',
      staticMember: false,
      rackId: '',
      pollTimeoutMs: 0,
      pollDelayMs: 250,
      maxPollIntervalMs: 300000,
    });
    expect(coordinationSettings(META, group)).toEqual({
      sessionTimeoutMs: 0,
      heartbeatIntervalMs: 5000,
      assignmentIntervalMs: 1000,
    });

    group = removeGroupSetting(group, 'sessionTimeoutMs');
    expect(group.settings).toEqual({ pollDelayMs: 250 });
    expect(coordinationSettings(META, group).sessionTimeoutMs).toBe(45000);
  });

  // Parity with the "+ Add setting" menu of `renderGroupOptional()` of
  // simulator.html on initial-setup: the settings not added yet, in the order
  // of `GROUP_OPTIONAL_FIELDS`, with their defaults as hints.
  it('offers the settings not added yet, with their defaults', () => {
    expect(addGroupSettingOptions(META, emptyGroup())).toEqual([
      { key: 'sessionTimeoutMs', label: 'Session Timeout (ms)', hint: '45000' },
      { key: 'heartbeatIntervalMs', label: 'Heartbeat Interval (ms)', hint: '5000' },
      { key: 'assignmentIntervalMs', label: 'Assignment Interval (ms)', hint: '1000' },
      { key: 'pollTimeoutMs', label: 'Poll Timeout (ms)', hint: '0' },
      { key: 'pollDelayMs', label: 'Poll Delay (ms)', hint: '1000' },
      { key: 'maxPollIntervalMs', label: 'Max Poll Interval (ms)', hint: '300000' },
    ]);
    const group = addGroupSetting(META, addGroupSetting(META, emptyGroup(), 'pollDelayMs'), 'heartbeatIntervalMs');
    expect(addGroupSettingOptions(META, group).map((o) => o.key)).toEqual([
      'sessionTimeoutMs',
      'assignmentIntervalMs',
      'pollTimeoutMs',
      'maxPollIntervalMs',
    ]);
    const all = [
      'sessionTimeoutMs',
      'heartbeatIntervalMs',
      'assignmentIntervalMs',
      'pollTimeoutMs',
      'pollDelayMs',
      'maxPollIntervalMs',
    ] as const;
    const full = all.reduce((g, key) => addGroupSetting(META, g, key), emptyGroup());
    expect(addGroupSettingOptions(META, full)).toEqual([]);
  });
});

describe('group protocol and assignor', () => {
  // Parity with `setGroupProtocol(value)`, `setGroupAssignor(value)`,
  // `setGroupCustomAssignor(value)` and `setGroupStatic(value)` of
  // simulator.html on initial-setup, run in this sequence from a group on
  // `uniform`.
  it('keeps the assignor valid for the protocol', () => {
    let group = pickGroupAssignor(META, emptyGroup(), 'uniform');
    expect(group.assignor).toEqual('uniform');

    group = setGroupProtocol(META, group, 'classic');
    expect(group).toMatchObject({ protocol: 'classic', assignor: 'range' });

    group = pickGroupAssignor(META, group, 'sticky');
    group = setGroupProtocol(META, group, 'consumer');
    expect(group).toMatchObject({ protocol: 'consumer', assignor: 'sticky' });

    group = pickGroupAssignor(META, group, CUSTOM_OPTION);
    expect(group.assignor).toEqual('sticky');

    group = setGroupCustomAssignor(group, 'my-assignor');
    group = pickGroupAssignor(META, group, CUSTOM_OPTION);
    expect(group.assignor).toEqual('my-assignor');

    group = pickGroupAssignor(META, group, 'range');
    expect(group.assignor).toEqual('range');

    group = setGroupStatic(group, true);
    expect(group).toMatchObject({ protocol: 'consumer', assignor: 'range', staticMember: true });
  });

  it('starts an empty custom name when leaving a preset', () => {
    expect(pickGroupAssignor(META, emptyGroup(), CUSTOM_OPTION).assignor).toEqual('');
  });
});

describe('consumers', () => {
  // Parity with `duplicateLastConsumer()`, `addConsumer()`,
  // `addOverride(id, key)`, `setOverride(id, key, value)`,
  // `removeConsumer(id)`, `consumerName(entry)`, `effectiveFor(entry)` and
  // `renderConsumerChips(entry)` of simulator.html on initial-setup, run in
  // this sequence.
  it('adds, duplicates and removes consumers', () => {
    let group = duplicateLastConsumer(emptyGroup());
    expect(group.consumers).toEqual([{ key: 0, expanded: false, overrides: {} }]);

    group = addConsumer(group);
    group = addOverride(META, group, 1, 'pollDelayMs');
    group = setOverride(META, group, 1, 'pollDelayMs', 10);
    group = addOverride(META, group, 1, 'staticMember');
    group = setOverride(META, group, 1, 'staticMember', true);
    group = duplicateLastConsumer(group);
    expect(group.consumers).toEqual([
      { key: 0, expanded: false, overrides: {} },
      { key: 1, expanded: false, overrides: { pollDelayMs: 10, staticMember: true } },
      { key: 2, expanded: false, overrides: { pollDelayMs: 10, staticMember: true } },
    ]);
    expect(group.consumers[2]?.overrides).not.toBe(group.consumers[1]?.overrides);

    group = removeConsumer(group, 0);
    expect(group.consumers.map((c) => c.key)).toEqual([1, 2]);
    expect(group.nextConsumerKey).toBe(3);
    expect(group.consumers.map((_, i) => consumerName(i))).toEqual(['consumer-0', 'consumer-1']);

    const first = group.consumers[0]!;
    expect(effectiveSettings(META, group, first)).toEqual({
      protocol: 'consumer',
      assignor: 'uniform',
      staticMember: true,
      rackId: '',
      pollTimeoutMs: 0,
      pollDelayMs: 10,
      maxPollIntervalMs: 300000,
    });
    expect(consumerChips(first)).toEqual([
      { key: 'staticMember', label: 'static', value: 'on' },
      { key: 'pollDelayMs', label: 'pollDelay', value: '10' },
    ]);
    expect(inheritsAll(first)).toBe(false);
  });

  it('shows no chips and inherits everything without overrides', () => {
    const consumer = { key: 0, expanded: false, overrides: {} };
    expect(consumerChips(consumer)).toEqual([]);
    expect(inheritsAll(consumer)).toBe(true);
  });

  it('shows every override as a chip, in menu order', () => {
    const consumer = {
      key: 0,
      expanded: false,
      overrides: {
        maxPollIntervalMs: 2000,
        pollTimeoutMs: 5,
        rackId: 'r1',
        assignor: 'uniform',
        protocol: 'classic' as const,
        pollDelayMs: 0,
        staticMember: false,
      },
    };
    expect(consumerChips(consumer).map((c) => `${c.label}=${c.value}`)).toEqual([
      'protocol=classic',
      'assignor=uniform',
      'static=off',
      'rack=r1',
      'pollTimeout=5',
      'pollDelay=0',
      'maxPoll=2000',
    ]);
    expect(overrideKeys(consumer)).toEqual([
      'maxPollIntervalMs',
      'pollTimeoutMs',
      'rackId',
      'assignor',
      'protocol',
      'pollDelayMs',
      'staticMember',
    ]);
  });

  it('toggles a consumer open and closed', () => {
    let group = addConsumer(addConsumer(emptyGroup()));
    group = toggleConsumer(group, 1);
    expect(group.consumers.map((c) => c.expanded)).toEqual([false, true]);
    group = toggleConsumer(group, 1);
    expect(group.consumers.map((c) => c.expanded)).toEqual([false, false]);
  });

  // Parity with `chipValue(key, value)` of simulator.html on initial-setup,
  // run on these inputs.
  it.each([
    ['staticMember', true, 'on'],
    ['staticMember', false, 'off'],
    ['pollDelayMs', 10, '10'],
    ['protocol', 'classic', 'classic'],
    ['assignor', 'range', 'range'],
    ['assignor', '', ''],
    ['assignor', 'mine', 'mine'],
    ['rackId', '', ''],
  ] as const)('formats %s %o as %j', (key, value, text) => {
    expect(formatSetting(key, value)).toBe(text);
  });
});

describe('overrides', () => {
  // Parity with `addOverride(id, key)`, `setOverride(id, key, value)` and
  // `removeOverride(id, key)` of simulator.html on initial-setup, run in this
  // sequence.
  it('overrides the protocol and keeps the assignor valid for it', () => {
    let group = addConsumer(emptyGroup());
    group = addOverride(META, group, 0, 'protocol');
    expect(overridesOf(group)).toEqual({ protocol: 'consumer' });

    group = pickGroupAssignor(META, group, 'uniform');
    group = setOverride(META, group, 0, 'protocol', 'classic');
    expect(overridesOf(group)).toEqual({ protocol: 'classic', assignor: 'range' });

    group = setOverride(META, group, 0, 'protocol', 'consumer');
    expect(overridesOf(group)).toEqual({ protocol: 'consumer', assignor: 'range' });

    group = pickOverrideAssignor(META, group, 0, CUSTOM_OPTION);
    expect(overridesOf(group)).toEqual({ protocol: 'consumer', assignor: '' });

    group = setOverride(META, group, 0, 'assignor', 'x');
    group = pickOverrideAssignor(META, group, 0, CUSTOM_OPTION);
    expect(overridesOf(group)).toEqual({ protocol: 'consumer', assignor: 'x' });

    group = removeOverride(group, 0, 'protocol');
    expect(overridesOf(group)).toEqual({ assignor: 'x' });
  });

  it('adds an assignor override at the inherited value and keeps it across a protocol switch', () => {
    let group = pickGroupAssignor(META, setGroupProtocol(META, emptyGroup(), 'classic'), 'range');
    group = addConsumer(group);
    group = addOverride(META, group, 0, 'assignor');
    expect(overridesOf(group)).toEqual({ assignor: 'range' });

    group = setOverride(META, group, 0, 'protocol', 'consumer');
    expect(overridesOf(group)).toEqual({ assignor: 'range', protocol: 'consumer' });
  });

  it('replaces a custom assignor that the classic protocol refuses', () => {
    let group = addConsumer(emptyGroup());
    group = addOverride(META, group, 0, 'assignor');
    group = pickOverrideAssignor(META, group, 0, CUSTOM_OPTION);
    group = setOverride(META, group, 0, 'assignor', 'mine');
    group = addOverride(META, group, 0, 'protocol');
    group = setOverride(META, group, 0, 'protocol', 'classic');
    expect(overridesOf(group)).toEqual({ assignor: 'range', protocol: 'classic' });
  });

  it('overrides an inherited assignor that the new protocol refuses, and leaves one it accepts', () => {
    let group = addConsumer(pickGroupAssignor(META, emptyGroup(), 'uniform'));
    group = addOverride(META, group, 0, 'protocol');
    group = setOverride(META, group, 0, 'protocol', 'classic');
    expect(overridesOf(group)).toEqual({ protocol: 'classic', assignor: 'range' });

    let classic = addConsumer(pickGroupAssignor(META, setGroupProtocol(META, emptyGroup(), 'classic'), 'sticky'));
    classic = setOverride(META, classic, 0, 'protocol', 'consumer');
    expect(overridesOf(classic)).toEqual({ protocol: 'consumer' });
  });

  it('leaves the group alone for an unknown consumer', () => {
    const group = addConsumer(emptyGroup());
    expect(pickOverrideAssignor(META, group, 42, 'range')).toBe(group);
    expect(setOverride(META, group, 42, 'rackId', 'r1').consumers).toEqual(group.consumers);
  });

  // Parity with `renderAddOverrideDropdown(entry)` of simulator.html on
  // initial-setup: the settings not overridden yet, in the order of
  // `OVERRIDE_FIELDS`, with the values they inherit as hints.
  it('offers the settings not overridden yet, with the values they inherit', () => {
    let group = setGroupStatic(addGroupSetting(META, emptyGroup(), 'pollDelayMs'), true);
    group = addConsumer(group);
    group = setOverride(META, group, 0, 'rackId', 'r1');
    expect(addOverrideOptions(META, group, group.consumers[0]!)).toEqual([
      { key: 'protocol', label: 'Protocol', hint: 'consumer' },
      { key: 'assignor', label: 'Assignor', hint: 'uniform' },
      { key: 'staticMember', label: 'Static Membership', hint: 'on' },
      { key: 'pollTimeoutMs', label: 'Poll Timeout (ms)', hint: '0' },
      { key: 'pollDelayMs', label: 'Poll Delay (ms)', hint: '1000' },
      { key: 'maxPollIntervalMs', label: 'Max Poll Interval (ms)', hint: '300000' },
    ]);
  });
});

// A classic group on `range`, static, with the max poll interval added, and
// two consumers, the second on the consumer protocol with a rack and a poll
// timeout.
function mixedGroup(): GroupForm {
  let group = pickGroupAssignor(META, setGroupProtocol(META, emptyGroup(), 'classic'), 'range');
  group = setGroupStatic(group, true);
  group = addGroupSetting(META, group, 'maxPollIntervalMs');
  group = addConsumer(addConsumer(group));
  group = addOverride(META, group, 1, 'protocol');
  group = setOverride(META, group, 1, 'protocol', 'consumer');
  group = addOverride(META, group, 1, 'rackId');
  group = setOverride(META, group, 1, 'rackId', 'r1');
  group = addOverride(META, group, 1, 'pollTimeoutMs');
  return setOverride(META, group, 1, 'pollTimeoutMs', parseIntInput('7'));
}

describe('start request', () => {
  // Parity with `collectConsumerConfigs()` of simulator.html on initial-setup,
  // run on the same group: the settings each consumer resolves to.
  it('resolves the settings of each consumer as the prototype did', () => {
    const group = mixedGroup();
    expect(group.consumers.map((c) => effectiveSettings(META, group, c))).toEqual([
      {
        protocol: 'classic',
        assignor: 'range',
        rackId: '',
        pollTimeoutMs: 0,
        pollDelayMs: 1000,
        maxPollIntervalMs: 300000,
        staticMember: true,
      },
      {
        protocol: 'consumer',
        assignor: 'range',
        rackId: 'r1',
        pollTimeoutMs: 7,
        pollDelayMs: 1000,
        maxPollIntervalMs: 300000,
        staticMember: true,
      },
    ]);
  });

  // The prototype posted the settings of every consumer resolved; the
  // request sends the defaults of the group and the overrides of each
  // consumer, and the core resolves them.
  it('sends the topics, one producer, and the group with its defaults and overrides', () => {
    const config: ConfigForm = { ratePerPartition: 2, groups: [setNumTopics(mixedGroup(), 2)] };

    expect(buildStartRequest(META, config)).toEqual({
      topics: [
        { name: 'kaos-1', partitions: 36 },
        { name: 'kaos-2', partitions: 36 },
      ],
      producers: [{ topics: ['kaos-1', 'kaos-2'], ratePerPartition: 2 }],
      groups: [
        {
          id: 'kaos',
          subscribedTopics: ['kaos-1', 'kaos-2'],
          sessionTimeoutMs: 45000,
          heartbeatIntervalMs: 5000,
          assignmentIntervalMs: 1000,
          consumerDefaults: {
            protocol: 'classic',
            assignor: 'range',
            staticMember: true,
            rackId: '',
            pollTimeoutMs: 0,
            pollDelayMs: 1000,
            maxPollIntervalMs: 300000,
          },
          consumers: [{ overrides: {} }, { overrides: { protocol: 'consumer', rackId: 'r1', pollTimeoutMs: 7 } }],
        },
      ],
    });
  });

  it('sends the name of an assignor override, and a custom name', () => {
    let group = pickGroupAssignor(META, addConsumer(emptyGroup()), CUSTOM_OPTION);
    group = setGroupCustomAssignor(group, 'mine');
    group = setOverride(META, group, 0, 'assignor', 'uniform');
    const request = buildStartRequest(META, { ratePerPartition: 2, groups: [group] });

    expect(request.groups?.[0]?.consumerDefaults?.assignor).toBe('mine');
    expect(request.groups?.[0]?.consumers).toEqual([{ overrides: { assignor: 'uniform' } }]);
  });

  it('writes to the topics of every group with one producer', () => {
    const config: ConfigForm = {
      ratePerPartition: 5,
      groups: [setPartitions(newGroup(META, 'a'), 3), setNumTopics(newGroup(META, 'b'), 2)],
    };
    const request = buildStartRequest(META, config);

    expect(request.topics).toEqual([
      { name: 'a-1', partitions: 3 },
      { name: 'b-1', partitions: 36 },
      { name: 'b-2', partitions: 36 },
    ]);
    expect(request.producers).toEqual([{ topics: ['a-1', 'b-1', 'b-2'], ratePerPartition: 5 }]);
    expect(request.groups?.map((g) => [g.id, g.subscribedTopics, g.consumers?.length])).toEqual([
      ['a', ['a-1'], 3],
      ['b', ['b-1', 'b-2'], 3],
    ]);
  });

  it('has no producer without topics', () => {
    expect(buildStartRequest(META, { ratePerPartition: 2, groups: [] })).toEqual({
      topics: [],
      producers: [],
      groups: [],
    });
  });
});

// The spec that `GET /api/v1/simulator` answers for a started request: the
// assigned ids and the resolved `effective` settings.
function specOf(config: ConfigForm): SimulatorSpec {
  const request = buildStartRequest(META, config);
  return {
    topics: request.topics ?? [],
    producers: (request.producers ?? []).map((p) => ({
      id: 'producer-0',
      topics: p.topics,
      ratePerPartition: p.ratePerPartition,
    })),
    groups: config.groups.map((group, g) => {
      const r = request.groups![g]!;
      return {
        id: r.id,
        subscribedTopics: r.subscribedTopics,
        sessionTimeoutMs: r.sessionTimeoutMs,
        heartbeatIntervalMs: r.heartbeatIntervalMs,
        assignmentIntervalMs: r.assignmentIntervalMs,
        consumerDefaults: r.consumerDefaults!,
        consumers: group.consumers.map((c, i) => {
          const effective = effectiveSettings(META, group, c);
          return {
            id: consumerName(i),
            overrides: r.consumers![i]!.overrides!,
            effective,
          };
        }),
      };
    }),
  };
}

const running = (spec: SimulatorSpec): Simulator => ({ phase: 'RUNNING', spec, lastError: '' });

describe('rehydration', () => {
  it('rebuilds the config that was started, overrides included', () => {
    let group = setGroupId(setPartitions(setNumTopics(mixedGroup(), 3), 12), 'orders');
    group = addGroupSetting(META, group, 'heartbeatIntervalMs');
    group = setGroupSetting(group, 'heartbeatIntervalMs', 3000);
    group = removeGroupSetting(group, 'maxPollIntervalMs');
    group = addGroupSetting(META, group, 'maxPollIntervalMs');
    group = setGroupSetting(group, 'maxPollIntervalMs', 90000);
    group = setOverride(META, group, 0, 'assignor', 'sticky');
    group = setOverride(META, group, 0, 'maxPollIntervalMs', 5000);
    group = toggleConsumer(group, 0);
    const config: ConfigForm = { ratePerPartition: 4, groups: [group] };

    const rehydrated = configFromSimulator(META, running(specOf(config)));

    expect(rehydrated.ratePerPartition).toBe(4);
    const g = rehydrated.groups[0]!;
    expect(g).toMatchObject({
      id: 'orders',
      numTopics: 3,
      partitions: 12,
      protocol: 'classic',
      assignor: 'range',
      staticMember: true,
      rackId: '',
      nextConsumerKey: 2,
    });
    // Parity with the rehydration of simulator.html on initial-setup: a
    // setting is added when it differs from its default, in menu order.
    expect(groupSettingKeys(g)).toEqual(['heartbeatIntervalMs', 'maxPollIntervalMs']);
    expect(g.settings).toEqual({ heartbeatIntervalMs: 3000, maxPollIntervalMs: 90000 });
    // The prototype restored the number of consumers only; the spec has
    // their overrides, which come back in menu order.
    expect(g.consumers).toEqual([
      { key: 0, expanded: false, overrides: { assignor: 'sticky', maxPollIntervalMs: 5000 } },
      { key: 1, expanded: false, overrides: { protocol: 'consumer', rackId: 'r1', pollTimeoutMs: 7 } },
    ]);
    expect(buildStartRequest(META, rehydrated)).toEqual(buildStartRequest(META, config));
  });

  it('shows a custom assignor name as custom', () => {
    let group = setGroupCustomAssignor(addConsumer(emptyGroup()), 'mine');
    group = setOverride(META, group, 0, 'protocol', 'classic');
    group = setOverride(META, group, 0, 'assignor', 'cooperative-sticky');
    const g = configFromSimulator(META, running(specOf({ ratePerPartition: 2, groups: [group] }))).groups[0]!;

    expect(g.assignor).toEqual('mine');
    expect(g.consumers[0]?.overrides).toEqual({ protocol: 'classic', assignor: 'cooperative-sticky' });
  });

  it('reads each group of the spec, and the default partitions without topics', () => {
    const spec = specOf({ ratePerPartition: 2, groups: [newGroup(META, 'a'), setNumTopics(newGroup(META, 'b'), 0)] });
    const config = configFromSimulator(META, running(spec));

    expect(config.groups.map((g) => [g.id, g.numTopics, g.partitions, g.consumers.length])).toEqual([
      ['a', 1, 36, 3],
      ['b', 0, 36, 3],
    ]);
  });

  it('falls back to the default rate without a producer', () => {
    const spec = { ...specOf({ ratePerPartition: 7, groups: [newGroup(META)] }), producers: [] };
    expect(configFromSimulator(META, running(spec)).ratePerPartition).toBe(2);
  });

  it('gives the initial config for a spec without groups', () => {
    const empty = running({ topics: [], producers: [], groups: [] });
    expect(configFromSimulator(META, empty)).toEqual(initialConfig(META));
  });

  // Parity with the start-up of simulator.html on initial-setup, which
  // rehydrated the form only when the phase was RUNNING.
  it.each(['IDLE', 'STARTING', 'STOPPING'] as const)('gives the initial config in the phase %s', (phase) => {
    const spec = specOf({ ratePerPartition: 2, groups: [setGroupId(mixedGroup(), 'orders')] });
    expect(configFromSimulator(META, { ...running(spec), phase })).toEqual(initialConfig(META));
  });

  // An empty assignor name, the default of the protocol, only comes from a
  // spec started through the API. It shows as the first preset of the
  // protocol, as on a new group, like simulator.html on initial-setup.
  it('shows an empty assignor name of the spec as the first preset of its protocol', () => {
    const spec = specOf({ ratePerPartition: 2, groups: [addConsumer(addConsumer(emptyGroup()))] });
    const group = spec.groups[0]!;
    const withEmpty: SimulatorSpec = {
      ...spec,
      groups: [
        {
          ...group,
          consumerDefaults: { ...group.consumerDefaults, assignor: '' },
          consumers: [
            { ...group.consumers[0]!, overrides: { assignor: '' } },
            { ...group.consumers[1]!, overrides: { protocol: 'classic', assignor: '' } },
          ],
        },
      ],
    };
    const g = configFromSimulator(META, running(withEmpty)).groups[0]!;

    expect(g.assignor).toBe('uniform');
    expect(g.consumers.map((c) => c.overrides)).toEqual([
      { assignor: 'uniform' },
      { protocol: 'classic', assignor: 'range' },
    ]);

    const classic: SimulatorSpec = {
      ...spec,
      groups: [{ ...group, consumerDefaults: { ...group.consumerDefaults, protocol: 'classic', assignor: '' } }],
    };
    expect(configFromSimulator(META, running(classic)).groups[0]?.assignor).toBe('range');
  });
});

describe('startProblems', () => {
  // Parity with the guard of `startSimulation()` of simulator.html on
  // initial-setup, which refused to start a group without consumers.
  it('refuses to start a group without consumers', () => {
    const empty = removeConsumer(addConsumer(emptyGroup()), 0);
    const config: ConfigForm = { ratePerPartition: 2, groups: [newGroup(META, 'a'), setGroupId(empty, 'b')] };

    expect(startProblems(config)).toEqual([{ group: 1, message: 'Add at least one consumer before starting.' }]);
  });

  it('accepts groups that all have consumers', () => {
    expect(startProblems(initialConfig(META))).toEqual([]);
  });
});
