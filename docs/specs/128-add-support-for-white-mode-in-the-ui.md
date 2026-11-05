# Spec #128: Add support for white mode in the UI

## Problem

The UI has one theme, the dark one of the prototype. A user who works
in a light environment (a bright room, a projector, a light desktop,
a screenshot pasted into a light document) cannot switch to a light
UI. On a projector in particular, the dark UI with its dim greys
washes out.

The palette is not in one place, so a light theme cannot be added by
changing `tokens.css` alone:

- `tokens.css` holds the 21 colour tokens of the prototype's `:root`.
- About 50 colours are hard-coded as dark-only `rgba(...)` in
  `rows.css`, `config.css` and `controls.css`:
  - the overlay fades over `rgba(10,14,20,…)`, which is `--bg`;
  - the cell tracks in `rgba(255,255,255,…)`;
  - the black text and box shadows;
  - the greys in `rgba(107,122,141,…)`, which is the prototype's
    `--text-muted`;
  - the state borders and tracks in `rgba(<state colour>,…)`;
  - the `#0a0e14` text of Start and Stop.
- `components/rows/Legend.tsx` sets the borders of its swatches inline
  in `rgba(...)`.

## Proposal

Two themes, **Dark** (today's) and **Light**, plus **System**, which
follows the operating system. The user picks one in the ⚙ menu, and
the choice persists in `kaos.uiSettings`.

### Choosing the theme

- The ⚙ menu gets a second row, **Theme**, under Strip Height. It has
  the same segmented control (`.ui-seg`, `role="radiogroup"`) with
  three choices: `System`, `Light` and `Dark`.
- `uiSettingsStore` gains `theme: 'system' | 'light' | 'dark'` and
  `setTheme(theme)`. `kaos.uiSettings` becomes
  `{"stripHeightPreset": "M", "theme": "dark"}`.
  - Each setter writes both fields. Today, `setStripHeightPreset`
    writes only its own field, so it would drop the theme.
  - The two fields are read separately. A missing or invalid
    `stripHeightPreset` gives `M`, and a missing or invalid `theme`
    gives the default theme. An old value without `theme` keeps its
    strip height.
- The **default** theme is `dark` (open question 1). An existing user
  sees no change until they pick another theme.
- `system` resolves through
  `matchMedia('(prefers-color-scheme: light)')`. While `system` is
  chosen, the UI follows a change of the OS setting live, through the
  query's `change` event, without a reload.
- The resolved theme is set on the root element:
  `<html data-theme="dark|light">`. A new module, `state/theme.ts`,
  holds this logic: `resolveTheme(choice, prefersLight)` and
  `applyTheme(choice)`, which sets the attribute and subscribes to the
  media query. `main.tsx` calls `applyTheme` with the store's theme
  and calls it again whenever the theme changes.
- A query parameter `?theme=light|dark|system` overrides the stored
  choice for that page load without saving it. With it, the factory's
  headless captures and a reviewer can show a theme on a fresh
  browser profile (open question 3).

### No flash of the wrong theme

- `index.html` gets a classic inline `<script>` in `<head>`, before
  the bundle and its CSS. It reads `?theme`, then
  `localStorage['kaos.uiSettings'].theme`, resolves `system` with
  `matchMedia`, and sets `data-theme` on `<html>` before the first
  paint. It is wrapped in `try`/`catch`: with a storage that is
  unavailable or corrupt, the attribute is not set.
- `tokens.css` keeps the dark values on `:root`, so a page without the
  attribute (no JavaScript, or the script failed) is dark, as today.
  `:root[data-theme="light"]` overrides every colour token.
- Each theme sets `color-scheme` (`dark` on `:root`, `light` on the
  light block), so the scrollbars, the native inputs and the caret
  match. `index.html` adds `<meta name="color-scheme" content="dark
  light">`.
- The inline script repeats the resolution logic of `theme.ts` in a
  dozen lines. A test (see Tests) keeps the two in agreement.
  The server sets no Content-Security-Policy, so an inline script is
  allowed.

### Tokens: every colour comes from `tokens.css`

After this change, no CSS file other than `tokens.css`, and no
component, contains a colour literal (`#…`, `rgb(...)`, `rgba(...)`,
`white` or `black`). The hard-coded values become tokens and
`color-mix()` expressions, with these rules:

1. **An alpha of a token** is
   `color-mix(in srgb, var(--token) N%, transparent)`. In sRGB this is
   the same colour as `rgba(r,g,b,N/100)`, so the dark theme is
   identical.
   - The state borders, tracks, glows and fill tops derive from their
     state colour this way. For example,
     `rgba(251,191,36,0.38)` becomes
     `color-mix(in srgb, var(--yellow) 38%, transparent)`.
   - The overlay fades derive from `--bg`:
     `rgba(10,14,20,0.80)` becomes
     `color-mix(in srgb, var(--bg) 80%, transparent)`.
   - Each theme sets the base token, and the derived values follow
     it.
2. **A role without a token yet** gets one new token, set in both
   themes:

   | Token | Replaces | Dark | Light |
   |---|---|---|---|
   | `--neutral` | `rgba(107,122,141,…)` (the empty/idle/stopped greys) | `#6b7a8d` | `#5c6878` |
   | `--ink` | `rgba(255,255,255,…)` (the cell tracks, the empty cell) | `#ffffff` | `#1b2330` |
   | `--halo` | `rgba(0,0,0,…)` of the text shadows | `#000000` | `#ffffff` |
   | `--shadow-pop` | `0 4px 12px rgba(0,0,0,0.4)` (tooltip, menus) | as today | `0 4px 12px rgba(16,24,40,0.14)` |
   | `--shadow-badge` | `0 1px 2px rgba(0,0,0,0.35)` | as today | `0 1px 2px rgba(16,24,40,0.12)` |
   | `--on-solid` | `#0a0e14` (text of Start and Stop) | `#0a0e14` | `#ffffff` |
   | `--cell-hover` | `filter: brightness(1.35)` of a hovered cell | `brightness(1.35)` | `brightness(0.92)` |

   `--neutral` is a token of its own, not `--text-muted`, because
   PR #92 changes `--text-muted`. If the greys derived from it, the
   dark theme would no longer look the same.
3. **The cell fills split into ink and fill.** In dark, the state
   colours serve both as text (badges) and as the gauge fill of the
   cells. In light, a colour dark enough for AA text on white gives a
   dark fill, and the member name over it no longer reads. That is
   the case #65 deals with, and light would make it worse. So the
   fills get their own tokens, `--fill-assigned`, `--fill-pending`
   and `--fill-revoking`:
   - in dark, they are `var(--green)`, `var(--accent)` and
     `var(--yellow)`, so nothing changes;
   - in light, they are the bright dark-theme hues `#34d399`,
     `#4c9aff` and `#fbbf24`.

   Dark text reads on these (8.3, 5.6 and 9.9:1). The tracks, the
   fill gradients, the fill glows and the legend swatches derive from
   the fill tokens.
4. **`-dim` tokens** keep their literal value in dark. In light they
   are written as the 12–14% `color-mix` of the light state colour.
5. **The legend** swatches become CSS classes,
   `.legend-swatch.assigned`, `.pending` and `.revoking`, in
   `rows.css`, using the same tokens as the cells. The inline styles
   of `Legend.tsx` go.

The fades, the halos and the high-lag text treatment of #65 follow
the theme through `--bg` and `--halo`. Whatever #65 lands, it uses
these tokens and no literal.

### The light palette

The text pairs meet WCAG 2.x AA (4.5:1) on every surface they sit on.

| Token | Dark (unchanged) | Light | Light contrast |
|---|---|---|---|
| `--bg` | `#0a0e14` | `#f5f7fa` | |
| `--surface` | `#12171f` | `#ffffff` | |
| `--surface2` | `#1a2029` | `#eef1f5` | |
| `--surface3` | `#222a35` | `#e3e8ee` | |
| `--border` | `#2a3342` | `#cdd5df` | |
| `--border-subtle` | `#1e2736` | `#e1e6ec` | |
| `--text` | `#d4dce8` | `#1b2330` | ≥ 13:1 on all |
| `--text-muted` | `#6b7a8d` (#92: `#929fb0`) | `#4b5869` | 7.2 on surface, 5.9 on surface3 |
| `--text-dim` | `#4a5568` (#92: `#7d8a9c`) | `#5c6878` | 5.7 on surface, 5.0 on surface2, 4.6 on surface3 |
| `--accent` | `#4c9aff` | `#0a5fc2` | 6.1 on surface, 5.1 on its `-dim` badge |
| `--green` | `#34d399` | `#0b7350` | 5.9, 4.9 |
| `--yellow` | `#fbbf24` | `#8a5a00` | 5.9, 5.0 |
| `--red` | `#f87171` | `#c0262d` | 5.9, 4.9 |
| `--fault` | `#5cd5ff` | `#00708f` | 5.7, 4.8 |
| `--orange` | `#f97316` | `#ad4300` | 5.9, 4.9 |
| `--on-solid` on `--green` / `--red` | | `#ffffff` | 5.9 / 5.9 |

These ratios were computed by hand from the WCAG formula. The
contrast test below recomputes them, and the build may adjust a hex
value within a few points to pass it. The badges with a `-dim`
background are measured over `--surface`, their usual parent.

Out of scope: the WCAG AA of the dark theme. PR #92 delivers it, and
this spec does not change a dark value (open question 2).

### Work in two PRs

1. **`refactor(ui): Move the hard-coded colours of the UI to tokens`**:
   - rules 1 to 5 above with the dark values only, plus the
     no-literal test;
   - the dark theme renders pixel-identical, and nothing visible
     changes;
   - useful on its own: #65 and #92 then change tokens instead of
     scattered literals.
2. **`feat(ui): Add a light theme and a theme choice`**:
   - the light block of `tokens.css`;
   - `theme` in `uiSettingsStore`, `theme.ts` and the Theme row of
     the ⚙ menu;
   - the inline boot script and `?theme`;
   - the contrast test and `docs/ARCHITECTURE.md`.

## API and JSON

No change of the Java API, the HTTP API or its JSON. The only
persisted change is the browser-local `kaos.uiSettings` value, which
gains an optional `"theme"` field. Both directions are compatible:
- an old value without `theme` reads as the default theme;
- an old UI reading a new value ignores the unknown field.

## UX

- **⚙ menu:** under "Strip Height  [S][M][L]", a new row
  "Theme  [System][Light][Dark]", with the current choice selected.
  Clicking a choice switches the theme at once, without a reload, and
  keeps the menu open, like Strip Height.
- **Dark:** exactly today's UI.
- **Light:**
  - the page is `#f5f7fa`, and the sidebar, the rows and the menus
    are white;
  - the text is near-black, with mid greys for the labels;
  - the state badges are deep green, blue, amber, red, teal and
    orange on pale tints of the same hue;
  - the cells keep the bright fills of the dark theme on pale tracks,
    so the heatmap reads the same in both themes;
  - the overlays fade into the light page, and the name and Actions
    get a white halo instead of a black shadow;
  - Start and Stop are solid green and red with white text.
- **System:** whichever of the two the OS asks for. The UI switches
  live when the OS switches, for example at sunset with automatic
  appearance.
- **First paint:** the page appears in its theme. There is no dark
  flash before a light UI.

No prototype screenshot is attached: this session could not run the
capture tools. Build PR 2 publishes before and after shots of both
themes, using the `?theme` parameter.

## Alternatives

- **Follow `prefers-color-scheme` only, with no setting.** This needs
  no boot script. But a user cannot pick light on a dark OS (the
  projector case), and the default would change for every user with a
  light OS. Rejected.
- **A setting only, without System.** It is simpler, but loses
  following the OS, which most apps now offer, for about ten lines of
  code. Rejected.
- **CSS `@media (prefers-color-scheme)` blocks instead of
  `data-theme`.** They avoid the flash for System, but an explicit
  choice still needs an attribute, so the light tokens would be
  written twice (once under the media query, once under the
  attribute). Rejected for one block.
- **The `light-dark()` CSS function on every token.** It is compact,
  but needs `color-scheme` driven per element, and Safari supports it
  only from 17.5. A plain override block is easier to read and diff.
  Rejected.
- **One token per hard-coded value** (`--row-border-reconciling`,
  `--fade-left-hover`, …). That is about 50 new tokens, most of them
  an alpha of an existing one, and the light block would need all of
  them. With `color-mix` from the base token, the light theme sets
  about 30 values instead. Rejected.
- **Wait for PR #92.** David decided not to wait. The two touch the
  same tokens but different values: #92 changes two dark greys, and
  this spec adds the light block and replaces literals. Whichever
  lands second rebases.

## Architecture

`docs/ARCHITECTURE.md`, Frontend:

- **State:** `uiSettingsStore` holds the strip height and the theme,
  `{"stripHeightPreset": "M", "theme": "dark"}`, each field read on
  its own and defaulting on its own. `state/theme.ts` resolves and
  applies the theme.
- **Rows view:** `UiSettingsMenu` has two rows, Strip Height and
  Theme.
- **CSS:**
  - `tokens.css` holds the two themes: the dark one on `:root`, the
    light one on `:root[data-theme="light"]`, each with its
    `color-scheme`;
  - no other stylesheet and no component holds a colour literal, and
    the alphas are `color-mix` of a token;
  - the cell fills have their own tokens;
  - the inline script of `index.html` sets `data-theme` before the
    first paint, and `?theme` overrides the choice for one load;
  - "The visual result is identical to the prototype" becomes "the
    dark theme is identical to the prototype".
- **Module map row:** mentions the themes.

## Tests

- **Store** (`uiSettingsStore.test.ts`):
  - the default theme;
  - a valid theme, an invalid theme and a missing theme;
  - an old `{"stripHeightPreset":"L"}` keeps `L` and gets the default
    theme;
  - each setter writes both fields.
- **Theme** (`theme.test.ts`, jsdom with a stubbed `matchMedia`):
  - `resolveTheme` for each choice and OS preference;
  - `applyTheme` sets `data-theme`;
  - under `system`, a `change` event of the media query switches it;
  - leaving `system` unsubscribes;
  - `?theme` overrides the stored choice without writing it.
- **Boot script:** the test reads the inline script of `index.html`,
  runs it in jsdom for the matrix of stored choice, query parameter
  and OS preference (corrupt storage included), and checks the
  attribute equals what `resolveTheme` gives.
- **Menu** (`UiSettingsMenu` in `rows.test.tsx`):
  - the Theme radiogroup shows the stored choice;
  - a click selects it, sets the attribute and persists it.
- **No literal** (`styles.test.ts`): the test reads every
  `src/styles/*.css` except `tokens.css` and every `*.tsx`, and fails
  on a hex colour, `rgb(`, `rgba(`, `hsl(`, or `white`/`black` used as
  a value.
- **Contrast** (`contrast.test.ts`):
  - parses both blocks of `tokens.css`, resolving the
    `color-mix(... transparent)` badges over their parent surface;
  - asserts ≥ 4.5:1 for the text/background pairs listed above, for
    the light theme;
  - for the dark theme, only the pairs #92 fixes, once #92 has
    landed.
- **Dark unchanged:** PR 1 and PR 2 publish before and after
  screenshots of the dark theme. Because rule 1 keeps the values
  exact, they match pixel for pixel. PR 2 also publishes light shots
  of the idle UI, a running group with each state, an open ⚙ menu and
  an open Actions menu, a tooltip and an error banner.

## Open questions

1. **Default theme: Dark or System?** **Recommended: Dark.**
   - Dark keeps today's look for everyone, as the issue asks, and
     keeps the factory's headless screenshots dark: headless Chrome
     reports a light OS.
   - System is the more common default in apps, but it would turn
     the UI light for every user on a light OS on upgrade.
2. **The AA of the dark theme.** The acceptance asks for AA in both
   themes. **Recommended:** this spec delivers AA for light and leaves
   the dark values to #92, so "the dark theme looks the same" holds.
   The contrast test checks dark once #92 has landed. Otherwise, PR 2
   would have to carry #92's two greys.
3. **The `?theme=` query parameter.** **Recommended: keep it.** It
   costs a few lines, and it is how the factory and a reviewer show
   light on a fresh profile. The alternative is a Chrome flag in
   `capture.sh` together with a System default (see question 1).
4. **The light cell fills.** **Recommended:** use the bright
   dark-theme hues on light. The heatmap then looks the same in both
   themes, and dark text reads on the fills. The alternative is the
   deep AA hues as fills, which match the badges but make the
   member name over a lagging cell unreadable.
5. **Two PRs or one?** **Recommended: two.** The token refactor is
   provably invisible on its own and unblocks #65. The light theme is
   then a reviewable block of values plus the setting.
