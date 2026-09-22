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

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The backend of the dev loop: `./mvnw -pl kaos-server -am -DskipUi compile exec:java`.
const backend = 'http://localhost:8081';

export default defineConfig({
  plugins: [react()],
  build: {
    // Maven packages target/classes into the kaos-ui jar, and the server
    // serves the classpath directory /ui.
    outDir: 'target/classes/ui',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // The event stream is a long-lived response: no timeout, so that the
      // proxy never cuts it.
      '/api': {
        target: backend,
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
