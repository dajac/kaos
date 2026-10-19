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

import { DASH, formatNumber, groupStateBadge, groupTypeLabel } from '../../model/rows';
import { useGroup } from '../../state/simulationStore';
import { Legend } from './Legend';
import { UiSettingsMenu } from './UiSettingsMenu';

// The sticky header of the Rows view: the type and state badges of the group,
// its epoch and target epoch (none for a classic group), the
// legend and the ⚙ menu. Until the group is reported, it shows STARTING.

interface StatusItemProps {
  readonly id: string;
  readonly label: string;
  readonly valueId: string;
  readonly value: string;
}

function StatusItem({ id, label, valueId, value }: StatusItemProps) {
  return (
    <span className="status-item" id={id}>
      <span className="status-label">{label}</span>{' '}
      <span id={valueId} className="status-value">
        {value}
      </span>
    </span>
  );
}

export function GroupHeader({ groupId }: { readonly groupId: string | undefined }) {
  const group = useGroup(groupId ?? '');

  let status;
  if (group === undefined) {
    status = (
      <>
        <span id="status-type" className="status-badge s-type">
          {DASH}
        </span>
        <span id="status-state" className="status-badge s-idle">
          STARTING
        </span>
        <StatusItem id="status-item-epoch" label="epoch" valueId="status-epoch" value={DASH} />
        <StatusItem id="status-item-target" label="target" valueId="status-target-epoch" value={DASH} />
      </>
    );
  } else {
    const state = groupStateBadge(group.state);
    status = (
      <>
        <span id="status-type" className="status-badge s-type">
          {groupTypeLabel(group.type)}
        </span>
        <span id="status-state" className={`status-badge ${state.cls}`}>
          {state.text}
        </span>
        {/* DescribeGroups reports no generation for a classic group. */}
        {group.type === 'CLASSIC' ? null : (
          <>
            <StatusItem id="status-item-epoch" label="epoch" valueId="status-epoch" value={formatNumber(group.epoch)} />
            <StatusItem
              id="status-item-target"
              label="target"
              valueId="status-target-epoch"
              value={formatNumber(group.targetAssignmentEpoch)}
            />
          </>
        )}
      </>
    );
  }

  return (
    <div className="main-header" id="main-header">
      <div className="header-status">{status}</div>
      <Legend />
      <UiSettingsMenu />
    </div>
  );
}
