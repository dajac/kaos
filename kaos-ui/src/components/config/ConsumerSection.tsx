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

import type { Meta } from '../../api/client';
import {
  addOverride,
  addOverrideOptions,
  consumerChips,
  consumerName,
  effectiveSettings,
  inheritsAll,
  OVERRIDE_KINDS,
  overrideField,
  overrideKeys,
  pickOverrideAssignor,
  removeConsumer,
  removeOverride,
  setOverride,
  type ConsumerForm,
  type ConsumerSettingsForm,
  type GroupForm,
  type OverrideKey,
} from '../../model/configModel';
import { overrideFieldKey } from '../../model/violations';
import { useConfigStore } from '../../state/configStore';
import { AssignorField } from './AssignorField';
import { Dropdown } from './Dropdown';
import { FieldError } from './FieldError';
import { NumberInput } from './NumberInput';
import { protocolOptions, STATIC_OPTIONS } from './options';

// A consumer section: the header with the name, the protocol tag and the ×,
// the chips of the overrides while collapsed, and, expanded, the overrides in
// the order they were added, or the "inherits all settings" note, and the
// "+ Add override" menu.

export interface ConsumerSectionProps {
  readonly meta: Meta;
  readonly groupIndex: number;
  readonly group: GroupForm;
  readonly consumer: ConsumerForm;
  readonly index: number;
}

export function ConsumerSection({ meta, groupIndex, group, consumer, index }: ConsumerSectionProps) {
  const editGroup = useConfigStore((s) => s.editGroup);
  const toggle = useConfigStore((s) => s.toggleConsumer);
  const errors = useConfigStore((s) => s.fieldErrors);

  const effective = effectiveSettings(meta, group, consumer);
  const chips = consumerChips(consumer);
  const addOptions = addOverrideOptions(meta, group, consumer);
  const edit = (key: OverrideKey, update: (group: GroupForm, meta: Meta) => GroupForm) =>
    editGroup(groupIndex, update, overrideFieldKey(groupIndex, consumer.key, key));

  return (
    <div
      className={consumer.expanded ? 'config-section expanded' : 'config-section'}
      id={`section-consumer-${consumer.key}`}
    >
      {/* A div with the role of a button, so that the × inside it can be a real button. */}
      <div
        className="section-toggle"
        role="button"
        tabIndex={0}
        aria-expanded={consumer.expanded}
        onClick={() => toggle(groupIndex, consumer.key)}
        onKeyDown={(event) => {
          if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            toggle(groupIndex, consumer.key);
          }
        }}
      >
        <span className="section-chevron">&#9654;</span>
        <span className="section-title">{consumerName(index)}</span>
        <span className={effective.protocol === 'consumer' ? 'section-tag tag-consumer' : 'section-tag tag-classic'}>
          {effective.protocol}
        </span>
        <button
          type="button"
          className="section-remove"
          onClick={(event) => {
            event.stopPropagation();
            editGroup(groupIndex, (g) => removeConsumer(g, consumer.key));
          }}
          title="Remove consumer"
          aria-label={`Remove ${consumerName(index)}`}
        >
          &times;
        </button>
      </div>

      {consumer.expanded || chips.length === 0 ? null : (
        <div className="consumer-chips">
          {chips.map((chip) => (
            <span key={chip.key} className="chip">
              {chip.label}={chip.value}
            </span>
          ))}
        </div>
      )}

      <div className="section-body">
        {inheritsAll(consumer) ? (
          <div className="inherit-note">Inherits all settings from group.</div>
        ) : (
          overrideKeys(consumer).map((key) => (
            <OverrideRow
              key={key}
              meta={meta}
              overrideKey={key}
              consumer={consumer}
              effective={effective}
              error={errors[overrideFieldKey(groupIndex, consumer.key, key)]}
              onChange={(value) => edit(key, (g, m) => setOverride(m, g, consumer.key, key, value))}
              onPickAssignor={(option) => edit(key, (g, m) => pickOverrideAssignor(m, g, consumer.key, option))}
              onRemove={() => edit(key, (g) => removeOverride(g, consumer.key, key))}
            />
          ))
        )}
        {addOptions.length === 0 ? null : (
          <Dropdown
            options={addOptions.map((option) => ({ value: option.key, label: option.label, hint: option.hint }))}
            buttonLabel="+ Add override"
            addVariant
            onChange={(value) => editGroup(groupIndex, (g, m) => addOverride(m, g, consumer.key, value as OverrideKey))}
          />
        )}
      </div>
    </div>
  );
}

interface OverrideRowProps {
  readonly meta: Meta;
  readonly overrideKey: OverrideKey;
  readonly consumer: ConsumerForm;
  readonly effective: ConsumerSettingsForm;
  readonly error: string | undefined;
  readonly onChange: (value: ConsumerSettingsForm[OverrideKey]) => void;
  readonly onPickAssignor: (option: string) => void;
  readonly onRemove: () => void;
}

function OverrideRow(props: OverrideRowProps) {
  const { meta, overrideKey, consumer, effective, error, onChange, onPickAssignor, onRemove } = props;
  const field = overrideField(meta, overrideKey);
  const remove = (
    <button type="button" className="field-remove" title="Remove override — inherit from group" onClick={onRemove}>
      &times;
    </button>
  );

  const kind = OVERRIDE_KINDS[overrideKey];
  if (kind === 'assignor') {
    return (
      <AssignorField
        meta={meta}
        protocol={effective.protocol}
        value={effective.assignor}
        onPick={onPickAssignor}
        onCustomName={onChange}
        remove={remove}
        error={error}
      />
    );
  }

  const value = consumer.overrides[overrideKey];
  let control;
  switch (kind) {
    case 'protocol':
      control = <Dropdown options={protocolOptions(meta)} value={String(value)} onChange={onChange} />;
      break;
    case 'boolean':
      control = (
        <Dropdown options={STATIC_OPTIONS} value={String(value)} onChange={(option) => onChange(option === 'true')} />
      );
      break;
    case 'text':
      control = (
        <input
          type="text"
          className="field-input"
          value={String(value ?? '')}
          onChange={(event) => onChange(event.target.value)}
        />
      );
      break;
    case 'number':
      control = <NumberInput value={Number(value)} min={field.min} max={field.max} onChange={onChange} />;
      break;
  }

  return (
    <div className="field field-inline">
      <label className="field-label">{field.label}</label>
      <div className="field-control">
        {control}
        {remove}
      </div>
      <FieldError message={error} />
    </div>
  );
}
