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

import { newGroup } from '../../model/configModel';
import { META } from '../../model/meta.fixture';
import { ConsumerSection } from './ConsumerSection';
import { GroupSection } from './GroupSection';

describe('GroupSection', () => {
  it('takes the labels of the topics and partitions from /meta', () => {
    const meta = {
      ...META,
      fields: META.fields.map((f) => {
        if (f.key === 'numTopics') {
          return { ...f, label: 'Topics of the group' };
        }
        return f.key === 'partitions' ? { ...f, label: 'Width' } : f;
      }),
    };

    const html = renderToStaticMarkup(<GroupSection meta={meta} groupIndex={0} group={newGroup(meta)} />);

    expect(html).toContain('<label class="field-label">Topics of the group</label>');
    expect(html).toContain('<label class="field-label">Width</label>');
  });
});

describe('ConsumerSection', () => {
  it('has a header that the keyboard reaches, with the × as a button of its own', () => {
    const group = newGroup(META);
    const consumer = group.consumers[1];
    if (consumer === undefined) {
      throw new Error('the group has no consumer-1');
    }

    const html = renderToStaticMarkup(
      <ConsumerSection meta={META} groupIndex={0} group={group} consumer={consumer} index={1} />,
    );

    expect(html).toContain('<div class="section-toggle" role="button" tabindex="0" aria-expanded="false">');
    expect(html).toContain(
      '<button type="button" class="section-remove" title="Remove consumer" aria-label="Remove consumer-1">×</button>',
    );
  });
});
