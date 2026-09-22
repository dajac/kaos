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

// A test helper that records which components render in each commit, the way
// React DevTools' profiler does, through the global hook that React calls on
// every commit. It must be installed before `react-dom` is loaded: from
// `vi.hoisted`, or from a script that runs before the page's bundle.
//
// A component rendered in a commit when its fiber is new, or when React did
// work on it (the `PerformedWork` flag). A subtree whose fibers were not
// cloned in the commit, because React bailed out above it, did not render and
// is not walked.
//
// The tracker reads React internals: the fiber tags, the `PerformedWork` flag
// and the identity of `child` against `alternate.child`. These values were
// read from react-dom 19.3.0 (`react-dom-client.development.js`). Check them
// again on every React upgrade: a tracker that sees too few renders would let
// the tests that expect no render pass without proving anything. Only the
// tests that expect renders, such as the mount and "every lag changes" tests
// of `rerender.test.tsx`, would catch it.

/** A component that rendered: its name, and the keys of it and its ancestors, the closest first. */
export interface RenderedComponent {
  readonly name: string;
  readonly keys: readonly string[];
}

export interface RenderTracker {
  /** The components that rendered in each commit since the last `reset`. */
  readonly commits: RenderedComponent[][];
  reset(): void;
}

interface Fiber {
  readonly tag: number;
  readonly key: string | null;
  readonly type: unknown;
  readonly flags: number;
  readonly child: Fiber | null;
  readonly sibling: Fiber | null;
  readonly return: Fiber | null;
  readonly alternate: Fiber | null;
}

interface FiberRoot {
  readonly current: Fiber;
}

// The fiber tags of the components that run a function of the app.
const FUNCTION_COMPONENT = 0;
const FORWARD_REF = 11;
const SIMPLE_MEMO_COMPONENT = 15;
const COMPONENT_TAGS: ReadonlySet<number> = new Set([FUNCTION_COMPONENT, FORWARD_REF, SIMPLE_MEMO_COMPONENT]);

const PERFORMED_WORK = 1;

function nameOf(type: unknown): string {
  if (typeof type === 'function') {
    return (type as { displayName?: string }).displayName ?? type.name;
  }
  if (typeof type === 'object' && type !== null && 'render' in type) {
    return nameOf((type as { render: unknown }).render);
  }
  return 'Anonymous';
}

function keysOf(fiber: Fiber): string[] {
  const keys: string[] = [];
  for (let current: Fiber | null = fiber; current !== null; current = current.return) {
    if (current.key !== null) {
      keys.push(current.key);
    }
  }
  return keys;
}

function collect(fiber: Fiber, rendered: RenderedComponent[]): void {
  for (let child: Fiber | null = fiber; child !== null; child = child.sibling) {
    const previous = child.alternate;
    if (COMPONENT_TAGS.has(child.tag) && (previous === null || (child.flags & PERFORMED_WORK) !== 0)) {
      rendered.push({ name: nameOf(child.type), keys: keysOf(child) });
    }
    if (child.child !== null && (previous === null || child.child !== previous.child)) {
      collect(child.child, rendered);
    }
  }
}

/** Installs the hook of React DevTools, which records the components rendered by each commit. */
export function installRenderTracker(): RenderTracker {
  const tracker: RenderTracker = {
    commits: [],
    reset() {
      tracker.commits.length = 0;
    },
  };
  const hook = {
    supportsFiber: true,
    renderers: new Map<number, unknown>(),
    inject(renderer: unknown): number {
      const id = hook.renderers.size + 1;
      hook.renderers.set(id, renderer);
      return id;
    },
    onCommitFiberRoot(_id: number, root: FiberRoot): void {
      const rendered: RenderedComponent[] = [];
      const current = root.current;
      if (current.alternate === null || current.child !== current.alternate.child) {
        if (current.child !== null) {
          collect(current.child, rendered);
        }
      }
      tracker.commits.push(rendered);
    },
    onCommitFiberUnmount(): void {},
    onPostCommitFiberRoot(): void {},
  };
  (globalThis as { __REACT_DEVTOOLS_GLOBAL_HOOK__?: unknown }).__REACT_DEVTOOLS_GLOBAL_HOOK__ = hook;
  return tracker;
}
