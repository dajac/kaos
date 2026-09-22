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

import { isLocked, useConfigStore } from '../../state/configStore';
import { selectRowsError, selectShownGroupId, useSimulationStore } from '../../state/simulationStore';
import { GroupHeader } from './GroupHeader';
import { RowsGrid } from './RowsGrid';

// The main area: the empty state while no simulation runs, and the Rows view
// of the group from the start of a simulation until it has stopped. The
// banners float over it: the event stream is down, or a request failed.

export function RowsView() {
  const active = useConfigStore(isLocked);
  const groupId = useSimulationStore(selectShownGroupId);

  return (
    <main className="main" id="main-area">
      {active ? (
        <div id="view-wrapper">
          <GroupHeader groupId={groupId} />
          <RowsGrid groupId={groupId} />
        </div>
      ) : (
        <div className="empty-state" id="empty-state">
          <div className="empty-state-icon">&#9654;</div>
          <div className="empty-state-text">Configure and start a simulation</div>
        </div>
      )}
      <Banners />
    </main>
  );
}

function Banners() {
  const disconnected = useSimulationStore((s) => s.connection === 'disconnected');
  const error = useSimulationStore(selectRowsError);
  const dismiss = useSimulationStore((s) => s.dismissErrors);

  if (!disconnected && error === null) {
    return null;
  }
  return (
    <div className="rows-banners">
      {disconnected ? (
        <div className="rows-banner rows-banner-disconnected" role="status">
          <div className="rows-banner-text">Disconnected from the server. Reconnecting&hellip;</div>
        </div>
      ) : null}
      {error === null ? null : (
        <div className="rows-banner" role="alert">
          <div className="rows-banner-text">{error}</div>
          <button type="button" className="rows-banner-close" title="Dismiss" onClick={dismiss}>
            &times;
          </button>
        </div>
      )}
    </div>
  );
}
