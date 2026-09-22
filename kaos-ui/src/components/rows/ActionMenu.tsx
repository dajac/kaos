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

import type { ReactNode } from 'react';

import type { ConsumerSnapshot } from '../../api/client';
import { useSimulationStore } from '../../state/simulationStore';
import { useDropdown } from '../config/Dropdown';

// The "Actions ▾" menu of a consumer row: membership (stop or start, upgrade
// or downgrade) and fault injection (partition network or heal, freeze or
// unfreeze). A consumer that is out of the group only offers Start and the
// ways back from a fault.

interface ActionMenuProps {
  readonly groupId: string;
  readonly consumer: ConsumerSnapshot;
}

export function ActionMenu({ groupId, consumer }: ActionMenuProps) {
  const { open, setOpen, buttonRef, rootProps } = useDropdown('row-actions-dd');
  const runAction = useSimulationStore((s) => s.runAction);
  const setFault = useSimulationStore((s) => s.setFault);

  const { id, subscribed } = consumer;
  const isolated = consumer.faults.includes('network-isolation');
  const frozen = consumer.faults.includes('freeze');

  const item = (label: string, destructive: boolean, enabled: boolean, run: () => Promise<void>): ReactNode => (
    <button
      type="button"
      className={destructive ? 'chaos-item destructive' : 'chaos-item'}
      disabled={!enabled}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => {
        setOpen(false);
        void run();
      }}
    >
      {label}
    </button>
  );

  return (
    <div {...rootProps}>
      <button
        type="button"
        ref={buttonRef}
        className="dd-btn row-actions-btn"
        title="Actions"
        aria-label="Actions"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span className="dd-value">Actions</span>
        <span className="dd-chevron">&#9662;</span>
      </button>
      <div className="dd-menu chaos-menu">
        <div className="chaos-group-label">Membership</div>
        {subscribed
          ? item('Stop consumer', true, true, () => runAction(groupId, id, { type: 'leave' }))
          : item('Start consumer', false, true, () => runAction(groupId, id, { type: 'join' }))}
        {consumer.protocol === 'classic'
          ? item('Upgrade to consumer', false, subscribed, () =>
              runAction(groupId, id, { type: 'switch-protocol', protocol: 'consumer' }),
            )
          : item('Downgrade to classic', false, subscribed, () =>
              runAction(groupId, id, { type: 'switch-protocol', protocol: 'classic' }),
            )}

        <div className="chaos-group-label">Fault Injection</div>
        {isolated
          ? item('Heal network', false, true, () => setFault(groupId, id, 'network-isolation', false))
          : item('Partition network', true, subscribed, () => setFault(groupId, id, 'network-isolation', true))}
        {frozen
          ? item('Unfreeze consumer', false, true, () => setFault(groupId, id, 'freeze', false))
          : item('Freeze consumer', true, subscribed, () => setFault(groupId, id, 'freeze', true))}
      </div>
    </div>
  );
}
