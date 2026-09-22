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

import type { Meta } from '../../api/client';
import {
  addGroupSetting,
  addGroupSettingOptions,
  groupSettingField,
  groupSettingKeys,
  metaField,
  pickGroupAssignor,
  removeGroupSetting,
  setGroupCustomAssignor,
  setGroupId,
  setGroupProtocol,
  setGroupSetting,
  setGroupStatic,
  setNumTopics,
  setPartitions,
  type GroupForm,
  type GroupSettingKey,
} from '../../model/configModel';
import { groupFieldKey } from '../../model/violations';
import { useConfigStore } from '../../state/configStore';
import { AssignorField } from './AssignorField';
import { Dropdown } from './Dropdown';
import { FieldError } from './FieldError';
import { NumberInput } from './NumberInput';
import { protocolOptions, STATIC_OPTIONS } from './options';

// The Group section: the group id, the protocol, the assignor, the static
// membership, the topics and partitions, and the optional settings added
// with "+ Add setting", in the order they were added.

export interface GroupSectionProps {
  readonly meta: Meta;
  readonly groupIndex: number;
  readonly group: GroupForm;
}

export function GroupSection({ meta, groupIndex, group }: GroupSectionProps) {
  const [expanded, setExpanded] = useState(true);
  const editGroup = useConfigStore((s) => s.editGroup);
  const errors = useConfigStore((s) => s.fieldErrors);
  const edit = (field: Parameters<typeof groupFieldKey>[1], update: (group: GroupForm, meta: Meta) => GroupForm) =>
    editGroup(groupIndex, update, groupFieldKey(groupIndex, field));
  const error = (field: Parameters<typeof groupFieldKey>[1]) => errors[groupFieldKey(groupIndex, field)];

  const numTopics = metaField(meta, 'SIMULATOR', 'numTopics');
  const partitions = metaField(meta, 'TOPIC', 'partitions');
  const addOptions = addGroupSettingOptions(meta, group);

  return (
    <div className={expanded ? 'config-section expanded' : 'config-section'} id="section-group">
      <button className="section-toggle" onClick={() => setExpanded(!expanded)} type="button">
        <span className="section-chevron" aria-hidden="true">&#9654;</span>
        <span className="section-title">Group</span>
      </button>
      <div className="section-body">
        <div className="field field-inline">
          <label className="field-label">{metaField(meta, 'GROUP', 'id').label}</label>
          <input
            className="field-input"
            type="text"
            value={group.id}
            onChange={(event) => edit('id', (g) => setGroupId(g, event.target.value))}
          />
          <FieldError message={error('id')} />
        </div>

        <div className="field field-inline">
          <label className="field-label">{metaField(meta, 'CONSUMER', 'protocol').label}</label>
          <Dropdown
            options={protocolOptions(meta)}
            value={group.protocol}
            onChange={(value) => edit('protocol', (g, m) => setGroupProtocol(m, g, value as GroupForm['protocol']))}
          />
          <FieldError message={error('protocol')} />
        </div>

        <AssignorField
          meta={meta}
          protocol={group.protocol}
          value={group.assignor}
          onPick={(option) => edit('assignor', (g, m) => pickGroupAssignor(m, g, option))}
          onCustomName={(name) => edit('assignor', (g) => setGroupCustomAssignor(g, name))}
          error={error('assignor')}
        />

        <div className="field field-inline">
          <label className="field-label">{metaField(meta, 'CONSUMER', 'staticMember').label}</label>
          <Dropdown
            options={STATIC_OPTIONS}
            value={String(group.staticMember)}
            onChange={(value) => edit('staticMember', (g) => setGroupStatic(g, value === 'true'))}
          />
          <FieldError message={error('staticMember')} />
        </div>

        <div className="field field-inline">
          <label className="field-label">{numTopics.label}</label>
          <NumberInput
            value={group.numTopics}
            min={numTopics.min}
            max={numTopics.max}
            onChange={(value) => edit('numTopics', (g) => setNumTopics(g, value))}
          />
          <FieldError message={error('numTopics')} />
        </div>
        <div className="field field-inline">
          <label className="field-label">{partitions.label}</label>
          <NumberInput
            value={group.partitions}
            min={partitions.min}
            max={partitions.max}
            onChange={(value) => edit('partitions', (g) => setPartitions(g, value))}
          />
          <FieldError message={error('partitions')} />
        </div>

        <div>
          {groupSettingKeys(group).map((key) => (
            <GroupSettingRow
              key={key}
              meta={meta}
              settingKey={key}
              value={group.settings[key] ?? 0}
              error={error(key)}
              onChange={(value) => edit(key, (g) => setGroupSetting(g, key, value))}
              onRemove={() => edit(key, (g) => removeGroupSetting(g, key))}
            />
          ))}
        </div>

        <div>
          {addOptions.length === 0 ? null : (
            <Dropdown
              options={addOptions.map((option) => ({ value: option.key, label: option.label, hint: option.hint }))}
              buttonLabel="+ Add setting"
              addVariant
              onChange={(value) => editGroup(groupIndex, (g, m) => addGroupSetting(m, g, value as GroupSettingKey))}
            />
          )}
        </div>
      </div>
    </div>
  );
}

interface GroupSettingRowProps {
  readonly meta: Meta;
  readonly settingKey: GroupSettingKey;
  readonly value: number;
  readonly error: string | undefined;
  readonly onChange: (value: number) => void;
  readonly onRemove: () => void;
}

function GroupSettingRow({ meta, settingKey, value, error, onChange, onRemove }: GroupSettingRowProps) {
  const field = groupSettingField(meta, settingKey);
  return (
    <div className="field field-inline">
      <label className="field-label">{field.label}</label>
      <div className="field-control">
        <NumberInput value={value} min={field.min} max={field.max} onChange={onChange} />
        <button type="button" className="field-remove" title="Remove — revert to default" onClick={onRemove}>
          &times;
        </button>
      </div>
      <FieldError message={error} />
    </div>
  );
}
