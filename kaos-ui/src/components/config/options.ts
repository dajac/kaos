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
import type { DropdownOption } from './Dropdown';

/** The options of a protocol dropdown: the protocols of `/meta`, by their ids. */
export function protocolOptions(meta: Meta): DropdownOption[] {
  return Object.keys(meta.assignors).map((protocol) => ({ value: protocol, label: protocol }));
}

/** The options of a static membership dropdown. */
export const STATIC_OPTIONS: readonly DropdownOption[] = [
  { value: 'false', label: 'off' },
  { value: 'true', label: 'on' },
];
