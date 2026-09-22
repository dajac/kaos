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

import { STRIP_HEIGHT_PRESETS, useUiSettings, type StripHeightPreset } from '../../state/uiSettingsStore';
import { useDropdown } from '../config/Dropdown';

// The ⚙ menu of the header: the height of the strips, S, M or L. The New/Old
// row of the prototype is gone with the Bento view.

const PRESETS = Object.keys(STRIP_HEIGHT_PRESETS) as StripHeightPreset[];

export function UiSettingsMenu() {
  const { open, setOpen, buttonRef, rootProps } = useDropdown('ui-settings-dd');
  const preset = useUiSettings((s) => s.stripHeightPreset);
  const setPreset = useUiSettings((s) => s.setStripHeightPreset);

  return (
    <div {...rootProps} id="ui-settings-dd">
      <button
        type="button"
        ref={buttonRef}
        className="dd-btn ui-settings-btn"
        title="UI settings"
        aria-label="UI settings"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span className="dd-value" aria-hidden="true">
          &#9881;
        </span>
      </button>
      <div className="dd-menu ui-settings-menu">
        <div className="ui-settings-group">UI Settings</div>
        <div className="ui-settings-row" id="ui-strip-height-row">
          <label className="ui-settings-label">Strip Height</label>
          <div className="ui-seg" id="ui-strip-height-seg" role="radiogroup" aria-label="Strip Height">
            {PRESETS.map((value) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={value === preset}
                className={value === preset ? 'ui-seg-btn selected' : 'ui-seg-btn'}
                data-height-preset={value}
                onClick={() => setPreset(value)}
              >
                {value}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
