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

import { useEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type RefObject } from 'react';

// The dropdown of the prototype (`.dd`): a button that shows the selected
// label and opens a menu of options, each with an optional hint. Opening a
// dropdown closes the others, and a click outside, Escape or moving the
// focus out of it closes it.

/** The open state of a `.dd` and the handlers of its root, for every menu of the prototype. */
export interface DropdownState {
  readonly open: boolean;
  setOpen(open: boolean): void;
  readonly buttonRef: RefObject<HTMLButtonElement | null>;
  readonly rootProps: {
    readonly className: string;
    readonly ref: RefObject<HTMLDivElement | null>;
    readonly onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
    readonly onBlur: (event: FocusEvent<HTMLDivElement>) => void;
  };
}

/** The state of a dropdown whose root has the classes `dd` and `extraClass`. */
export function useDropdown(extraClass?: string): DropdownState {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    // In the capture phase, like the prototype, so that opening another
    // dropdown closes this one first.
    const close = (event: MouseEvent) => {
      if (!(event.target instanceof Node) || !root.current?.contains(event.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('click', close, true);
    return () => document.removeEventListener('click', close, true);
  }, [open]);

  const classes = ['dd'];
  if (extraClass !== undefined) {
    classes.push(extraClass);
  }
  if (open) {
    classes.push('open');
  }

  return {
    open,
    setOpen,
    buttonRef,
    rootProps: {
      className: classes.join(' '),
      ref: root,
      onKeyDown: (event) => {
        if (open && event.key === 'Escape') {
          event.stopPropagation();
          setOpen(false);
          buttonRef.current?.focus();
        }
      },
      onBlur: (event) => {
        // Focus left the dropdown, as with Tab out of its last item.
        if (!(event.relatedTarget instanceof Node) || !root.current?.contains(event.relatedTarget)) {
          setOpen(false);
        }
      },
    },
  };
}

export interface DropdownOption {
  readonly value: string;
  readonly label: string;
  readonly hint?: string;
}

export interface DropdownProps {
  readonly options: readonly DropdownOption[];
  /** The selected value; `undefined` for an action menu, which selects nothing. */
  readonly value?: string;
  /** The text of the button; the label of the selected option by default. */
  readonly buttonLabel?: string;
  /** The "+ Add" variant: a dashed button for an action menu. */
  readonly addVariant?: boolean;
  readonly onChange: (value: string) => void;
}

export function Dropdown({ options, value, buttonLabel, addVariant = false, onChange }: DropdownProps) {
  const { open, setOpen, buttonRef, rootProps } = useDropdown();

  const selected = options.find((option) => option.value === value);
  const label = buttonLabel ?? selected?.label ?? value ?? '';

  return (
    <div {...rootProps}>
      <button
        type="button"
        ref={buttonRef}
        className={addVariant ? 'dd-btn dd-btn-add' : 'dd-btn'}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span className="dd-value">{label}</span>
        <span className="dd-chevron">&#9662;</span>
      </button>
      <div className="dd-menu">
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            className={option.value === value ? 'dd-item selected' : 'dd-item'}
            // Keeps the focus on the dropdown, so that a click on an item
            // does not blur it and close the menu before the click lands.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => {
              setOpen(false);
              onChange(option.value);
            }}
          >
            <span>{option.label}</span>
            {option.hint === undefined ? null : <span className="dd-item-hint">{option.hint}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}
