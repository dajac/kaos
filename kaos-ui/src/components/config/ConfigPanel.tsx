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

import { useEffect, useRef } from 'react';

import type { Meta } from '../../api/client';
import { addConsumer, duplicateLastConsumer, type GroupForm } from '../../model/configModel';
import { groupFieldKey } from '../../model/violations';
import { isLocked, useConfigStore } from '../../state/configStore';
import { ConsumerSection } from './ConsumerSection';
import { FieldError } from './FieldError';
import { GroupSection } from './GroupSection';

// The config panel of the sidebar (`#config-mode`): the sections of every
// group, the error banner and the footer with Start / Stop. While a
// simulation runs, the panel is locked: grayed out and not editable.

export function ConfigPanel() {
  const meta = useConfigStore((s) => s.meta);
  const groups = useConfigStore((s) => s.config?.groups);
  const locked = useConfigStore(isLocked);

  return (
    <div id="config-mode" className={locked ? 'locked' : undefined}>
      <div className="config-scroll" id="config-scroll">
        {meta === null || groups === undefined
          ? null
          : groups.map((group, groupIndex) => (
              <GroupConfig key={groupIndex} meta={meta} groupIndex={groupIndex} group={group} />
            ))}
      </div>
      <Banner />
      <Footer />
    </div>
  );
}

interface GroupConfigProps {
  readonly meta: Meta;
  readonly groupIndex: number;
  readonly group: GroupForm;
}

function GroupConfig({ meta, groupIndex, group }: GroupConfigProps) {
  const editGroup = useConfigStore((s) => s.editGroup);
  const consumersError = useConfigStore((s) => s.fieldErrors[groupFieldKey(groupIndex, 'consumers')]);
  // The consumer to scroll to once it is rendered: the one just added.
  const scrollTo = useRef<number | null>(null);

  useEffect(() => {
    if (scrollTo.current !== null) {
      document
        .getElementById(`section-consumer-${scrollTo.current}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      scrollTo.current = null;
    }
  }, [group.consumers.length]);

  const add = (update: (group: GroupForm) => GroupForm) => {
    scrollTo.current = group.nextConsumerKey;
    editGroup(groupIndex, update, groupFieldKey(groupIndex, 'consumers'));
  };

  return (
    <>
      <GroupSection meta={meta} groupIndex={groupIndex} group={group} />

      <div className="consumers-header">
        <span className="consumers-header-label">Consumers</span>
        <span className="consumers-header-count">({group.consumers.length})</span>
      </div>
      {consumersError === undefined ? null : (
        <div className="consumers-error">
          <FieldError message={consumersError} />
        </div>
      )}

      <div>
        {group.consumers.map((consumer, index) => (
          <ConsumerSection
            key={consumer.key}
            meta={meta}
            groupIndex={groupIndex}
            group={group}
            consumer={consumer}
            index={index}
          />
        ))}
      </div>

      <div className="add-consumer-row">
        <button className="btn-add-consumer" onClick={() => add(addConsumer)} type="button">
          + Add
        </button>
        <button className="btn-add-consumer" onClick={() => add(duplicateLastConsumer)} type="button">
          + Duplicate
        </button>
      </div>
    </>
  );
}

function Banner() {
  const loadError = useConfigStore((s) => s.loadError);
  const banner = useConfigStore((s) => s.banner);
  const details = useConfigStore((s) => s.bannerDetails);
  const dismiss = useConfigStore((s) => s.dismissBanner);

  const message = loadError ?? banner;
  if (message === null) {
    return null;
  }
  return (
    <div className="config-banner" role="alert">
      <div className="config-banner-text">
        <div>{message}</div>
        {details.map((detail) => (
          <div key={detail} className="config-banner-detail">
            {detail}
          </div>
        ))}
      </div>
      {loadError === null ? (
        <button type="button" className="config-banner-close" title="Dismiss" onClick={dismiss}>
          &times;
        </button>
      ) : null}
    </div>
  );
}

function Footer() {
  const phase = useConfigStore((s) => s.phase);
  const ready = useConfigStore((s) => s.config !== null);
  const start = useConfigStore((s) => s.start);
  const stop = useConfigStore((s) => s.stop);

  let button;
  switch (phase) {
    case 'IDLE':
      button = (
        <button className="btn btn-start" id="btn-start-sim" disabled={!ready} onClick={() => void start()}>
          Start
        </button>
      );
      break;
    case 'STARTING':
      button = (
        <button className="btn btn-start" id="btn-start-sim" disabled>
          Starting&hellip;
        </button>
      );
      break;
    case 'RUNNING':
      button = (
        <button className="btn btn-stop" id="btn-start-sim" onClick={() => void stop()}>
          Stop
        </button>
      );
      break;
    case 'STOPPING':
      button = (
        <button className="btn btn-stop" id="btn-start-sim" disabled>
          Stopping&hellip;
        </button>
      );
      break;
  }

  return (
    <div className="sidebar-footer" id="config-footer">
      {button}
    </div>
  );
}
