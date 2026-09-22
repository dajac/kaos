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

import { useEffect, useMemo, useRef, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';

import { useGroup, useSimulationStore } from '../../state/simulationStore';
import { STRIP_HEIGHT_PRESETS, useUiSettings } from '../../state/uiSettingsStore';
import { CellTooltipContext, createCellTooltip } from './cellTooltip';
import { ConsumerRow, type TopicBand } from './ConsumerRow';

// The rows of a group, one per consumer, with the one tooltip of their cells.
// The strips are `--strip-height` high, the S/M/L setting of the ⚙ menu.

const NO_TOPICS: readonly TopicBand[] = [];

export function RowsGrid({ groupId }: { readonly groupId: string | undefined }) {
  const group = useGroup(groupId ?? '');
  const topicSpecs = useSimulationStore((s) => s.snapshot?.topics);
  const preset = useUiSettings((s) => s.stripHeightPreset);

  const tipRef = useRef<HTMLDivElement>(null);
  const tooltip = useMemo(() => createCellTooltip(() => tipRef.current), []);

  // The tooltip would dangle while the content moves under it.
  useEffect(() => {
    const hide = () => tooltip.hide();
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
    window.addEventListener('click', hide, true);
    return () => {
      window.removeEventListener('scroll', hide, true);
      window.removeEventListener('resize', hide);
      window.removeEventListener('click', hide, true);
    };
  }, [tooltip]);

  const subscribedTopics = group?.subscribedTopics;
  const topics = useMemo(() => {
    if (subscribedTopics === undefined) {
      return NO_TOPICS;
    }
    return subscribedTopics.map((name) => ({
      name,
      partitions: topicSpecs?.find((topic) => topic.name === name)?.partitions ?? 0,
    }));
  }, [subscribedTopics, topicSpecs]);

  const style = { '--strip-height': `${STRIP_HEIGHT_PRESETS[preset]}px` } as CSSProperties;

  return (
    <CellTooltipContext.Provider value={tooltip}>
      <div className="rows-grid" id="rows-grid" style={style} onMouseOver={tooltip.over} onMouseOut={tooltip.out}>
        {group === undefined
          ? null
          : group.consumers.map((consumer) => (
              <ConsumerRow key={consumer.id} groupId={group.id} consumer={consumer} topics={topics} lag={group.lag} />
            ))}
      </div>
      {typeof document === 'undefined' ? null : createPortal(<div id="pcell-tip" role="tooltip" ref={tipRef} />, document.body)}
    </CellTooltipContext.Provider>
  );
}
