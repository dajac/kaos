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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { META } from '../../model/meta.fixture';
import { AssignorField } from './AssignorField';
import { Dropdown } from './Dropdown';

const noop = () => undefined;

describe('Dropdown', () => {
  it('renders the markup of the prototype, with the selected option and the hints', () => {
    const html = renderToStaticMarkup(
      <Dropdown
        options={[
          { value: 'consumer', label: 'consumer' },
          { value: 'classic', label: 'classic', hint: 'legacy' },
        ]}
        value="classic"
        onChange={noop}
      />,
    );

    expect(html).toBe(
      '<div class="dd"><button type="button" class="dd-btn"><span class="dd-value">classic</span>' +
        '<span class="dd-chevron">▾</span></button><div class="dd-menu">' +
        '<button type="button" class="dd-item"><span>consumer</span></button>' +
        '<button type="button" class="dd-item selected"><span>classic</span>' +
        '<span class="dd-item-hint">legacy</span></button></div></div>',
    );
  });

  it('renders an action menu with its own label', () => {
    const html = renderToStaticMarkup(
      <Dropdown options={[{ value: 'a', label: 'A' }]} buttonLabel="+ Add setting" addVariant onChange={noop} />,
    );

    expect(html).toContain('<button type="button" class="dd-btn dd-btn-add"><span class="dd-value">+ Add setting</span>');
    expect(html).not.toContain('selected');
  });
});

describe('AssignorField', () => {
  it('shows the presets of the protocol without a text input', () => {
    const html = renderToStaticMarkup(
      <AssignorField meta={META} protocol="classic" value="range" onPick={noop} onCustomName={noop} />,
    );

    expect(html).toMatch(/^<div class="field field-inline"><label class="field-label">Assignor<\/label>/);
    expect(html).toContain('<span class="dd-value">range</span>');
    expect(html).toContain('<span>cooperative-sticky</span>');
    expect(html).not.toContain('custom');
    expect(html).not.toContain('<input');
  });

  it('shows a custom name in a text input under the dropdown, and anchors the label to the top', () => {
    const html = renderToStaticMarkup(
      <AssignorField meta={META} protocol="consumer" value="mine" onPick={noop} onCustomName={noop} />,
    );

    expect(html).toMatch(/^<div class="field field-inline field-stacked">/);
    expect(html).toContain('<span class="dd-value">custom</span>');
    expect(html).toContain(
      '<input type="text" class="field-input" style="margin-top:6px" placeholder="custom assignor name" value="mine"/>',
    );
  });

  it('shows an empty custom name of the consumer protocol as custom', () => {
    const html = renderToStaticMarkup(
      <AssignorField meta={META} protocol="consumer" value="" onPick={noop} onCustomName={noop} />,
    );

    expect(html).toContain('<span class="dd-value">custom</span>');
    expect(html).toContain('placeholder="custom assignor name" value=""/>');
  });

  it('puts the × of an override next to the dropdown and shows its error', () => {
    const html = renderToStaticMarkup(
      <AssignorField
        meta={META}
        protocol="consumer"
        value="uniform"
        onPick={noop}
        onCustomName={noop}
        remove={<button className="field-remove">×</button>}
        error="unknown assignor"
      />,
    );

    expect(html).toContain('<div class="field-control"><div class="dd">');
    expect(html).toContain('<button class="field-remove">×</button></div>');
    expect(html).toContain('<div class="field-error" role="alert">unknown assignor</div>');
  });
});
