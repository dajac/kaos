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

import type { components, operations } from './schema';

// The client of the HTTP API v1. The request and response types come from
// the generated `schema.d.ts`. A request that the server answers with an
// error status throws an `ApiError`, which carries the problem detail
// (RFC 9457) when the body is one.

type JsonBody<R> = R extends { content: { 'application/json': infer B } } ? B : never;

export type Meta = JsonBody<operations['getMeta']['responses'][200]>;
export type Simulator = JsonBody<operations['getSimulator']['responses'][200]>;
export type StartRequest = operations['startSimulator']['requestBody']['content']['application/json'];
export type Status = JsonBody<operations['startSimulator']['responses'][202]>;
export type Problem = components['schemas']['Problem'];
export type Violation = components['schemas']['Violation'];

/** A request that failed: an error status, with its problem detail when the server sent one. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;

  constructor(status: number, message: string, problem?: Problem) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  /** The violations of a 400, empty for any other failure. */
  get violations(): readonly Violation[] {
    return this.problem?.violations ?? [];
  }
}

export interface ApiClient {
  getMeta(): Promise<Meta>;
  getSimulator(): Promise<Simulator>;
  /** Starts a simulation: 202 with the status, or an `ApiError` (400 with violations, 409). */
  startSimulator(request: StartRequest): Promise<Status>;
  /** Stops the simulation: 202 with the status, or `null` for a 204, when there is nothing to stop. */
  stopSimulator(): Promise<Status | null>;
}

export type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && 'status' in body && 'detail' in body;
}

async function failure(response: Response): Promise<ApiError> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = undefined;
  }
  if (isProblem(body)) {
    return new ApiError(response.status, body.detail, body);
  }
  return new ApiError(response.status, `${response.status} ${response.statusText}`.trim());
}

/** A client of the API under `base`, which is `/api/v1` of the page's origin by default. */
export function createClient(fetchFn: Fetch = (input, init) => fetch(input, init), base = '/api/v1'): ApiClient {
  async function request<T>(method: string, path: string, body?: unknown): Promise<T | null> {
    const init: RequestInit = { method };
    if (body !== undefined) {
      init.headers = { 'Content-Type': 'application/json' };
      init.body = JSON.stringify(body);
    }
    const response = await fetchFn(base + path, init);
    if (!response.ok) {
      throw await failure(response);
    }
    if (response.status === 204) {
      return null;
    }
    return (await response.json()) as T;
  }

  async function requestBody<T>(method: string, path: string, body?: unknown): Promise<T> {
    const result = await request<T>(method, path, body);
    if (result === null) {
      throw new ApiError(204, `${method} ${path} answered without a body`);
    }
    return result;
  }

  return {
    getMeta: () => requestBody<Meta>('GET', '/meta'),
    getSimulator: () => requestBody<Simulator>('GET', '/simulator'),
    startSimulator: (startRequest) => requestBody<Status>('POST', '/simulator', startRequest),
    stopSimulator: () => request<Status>('DELETE', '/simulator'),
  };
}
