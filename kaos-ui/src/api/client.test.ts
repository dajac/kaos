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

import { describe, expect, it } from 'vitest';

import { META } from '../model/meta.fixture';
import { ApiError, createClient, type Fetch } from './client';

interface Call {
  readonly url: string;
  readonly init: RequestInit | undefined;
}

function fakeFetch(response: () => Response): { fetch: Fetch; calls: Call[] } {
  const calls: Call[] = [];
  return {
    calls,
    fetch: async (url, init) => {
      calls.push({ url, init });
      return response();
    },
  };
}

const json = (status: number, body: unknown, type = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } });

const STATUS = { phase: 'STARTING', lastError: '', warnings: [] };

describe('createClient', () => {
  it('reads /meta under the base path', async () => {
    const { fetch, calls } = fakeFetch(() => json(200, META));

    const meta = await createClient(fetch).getMeta();

    expect(meta).toEqual(META);
    expect(calls).toEqual([{ url: '/api/v1/meta', init: { method: 'GET' } }]);
  });

  it('posts the start request as JSON and returns the status of the 202', async () => {
    const { fetch, calls } = fakeFetch(() => json(202, STATUS));
    const request = { topics: [], producers: [], groups: [] };

    const status = await createClient(fetch, '/base').startSimulator(request);

    expect(status).toEqual(STATUS);
    expect(calls[0]?.url).toBe('/base/simulator');
    expect(calls[0]?.init?.method).toBe('POST');
    expect(calls[0]?.init?.headers).toEqual({ 'Content-Type': 'application/json' });
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual(request);
  });

  it('throws the problem detail of an error, with its violations', async () => {
    const problem = {
      type: 'about:blank',
      title: 'Bad Request',
      status: 400,
      detail: 'The spec is invalid',
      violations: [{ field: 'groups[0].id', message: 'must not be blank' }],
    };
    const { fetch } = fakeFetch(() => json(400, problem, 'application/problem+json'));

    const error = await createClient(fetch)
      .startSimulator({ topics: [], producers: [], groups: [] })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.message).toBe('The spec is invalid');
    expect(apiError.problem).toEqual(problem);
    expect(apiError.violations).toEqual(problem.violations);
  });

  it('throws the status of an error whose body is not a problem', async () => {
    const { fetch } = fakeFetch(() => new Response('<html>', { status: 502, statusText: 'Bad Gateway' }));

    const error = (await createClient(fetch)
      .getSimulator()
      .catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe('502 Bad Gateway');
    expect(error.problem).toBeUndefined();
    expect(error.violations).toEqual([]);
  });

  it('stops with DELETE and returns null for a 204', async () => {
    const { fetch, calls } = fakeFetch(() => new Response(null, { status: 204 }));

    expect(await createClient(fetch).stopSimulator()).toBeNull();
    expect(calls).toEqual([{ url: '/api/v1/simulator', init: { method: 'DELETE' } }]);
  });

  it('stops with the status of a 202', async () => {
    const { fetch } = fakeFetch(() => json(202, { ...STATUS, phase: 'STOPPING' }));

    expect(await createClient(fetch).stopSimulator()).toEqual({ ...STATUS, phase: 'STOPPING' });
  });
});
