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

import '@fontsource-variable/dm-sans';
import '@fontsource-variable/jetbrains-mono';
import './styles/tokens.css';
import './styles/base.css';
import './styles/controls.css';
import './styles/config.css';
import './styles/rows.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { configStore } from './state/configStore';
import { simulationStore } from './state/simulationStore';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('The page has no #root element');
}

void configStore.getState().load();
simulationStore.getState().connect({ onStatus: (status) => configStore.getState().applyStatus(status) });

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
