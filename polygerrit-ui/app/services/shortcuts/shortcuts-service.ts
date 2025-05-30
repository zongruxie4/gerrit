/**
 * @license
 * Copyright 2021 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {Subscription} from 'rxjs';
import {distinctUntilChanged, map} from 'rxjs/operators';
import {
  createShortcutConfig,
  Shortcut,
  ShortcutHelpItem,
  ShortcutSection,
} from './shortcuts-config';
import {
  Binding,
  ComboKey,
  eventMatchesShortcut,
  isElementTarget,
  Key,
  Modifier,
  ShortcutOptions,
  shouldSuppress,
} from '../../utils/dom-util';
import {ReportingService} from '../gr-reporting/gr-reporting';
import {UserModel} from '../../models/user/user-model';
import {define} from '../../models/dependency';
import {isCharacterLetter, isUpperCase} from '../../utils/string-util';
import {Finalizable} from '../../types/types';

export {Shortcut, ShortcutSection};

export type SectionView = Array<{binding: string[][]; text: string}>;

export interface ShortcutListener {
  shortcut: Shortcut;
  listener: (e: KeyboardEvent) => void;
}

export function listen(
  shortcut: Shortcut,
  listener: (e: KeyboardEvent) => void
): ShortcutListener {
  return {shortcut, listener};
}

/**
 * The interface for listener for shortcut events.
 */
export type ShortcutViewListener = (
  viewMap?: Map<ShortcutSection, SectionView>
) => void;

function isComboKey(key: string): key is ComboKey {
  return Object.values(ComboKey).includes(key as ComboKey);
}

export const COMBO_TIMEOUT_MS = 1000;

export const shortcutsServiceToken =
  define<ShortcutsService>('shortcuts-service');

/**
 * Shortcuts service, holds all hosts, bindings and listeners.
 */
export class ShortcutsService implements Finalizable {
  /**
   * Keeps track of the components that are currently active such that we can
   * show a shortcut help dialog that only shows the shortcuts that are
   * currently relevant.
   */
  private readonly activeShortcuts = new Set<Shortcut>();

  /** Static map built in the constructor by iterating over the config. */
  private readonly bindings = new Map<Shortcut, Binding[]>();

  private readonly listeners = new Set<ShortcutViewListener>();

  /**
   * Stores the timestamp of the last combo key being pressed.
   * This enabled key combinations like 'g+o' where we can check whether 'g' was
   * pressed recently when 'o' is processed. Keys of this map must be items of
   * COMBO_KEYS. Values are Date timestamps in milliseconds.
   */
  private comboKeyLastPressed: {key?: ComboKey; timestampMs?: number} = {};

  /** Keeps track of the corresponding user preference. */
  // visible for testing
  shortcutsDisabled = false;

  private readonly keydownListener: (e: KeyboardEvent) => void;

  private readonly subscriptions: Subscription[] = [];

  private readonly config: Map<ShortcutSection, ShortcutHelpItem[]>;

  constructor(
    readonly userModel: UserModel,
    readonly reporting?: ReportingService
  ) {
    this.config = createShortcutConfig();
    for (const section of this.config.keys()) {
      const items = this.config.get(section) ?? [];
      for (const item of items) {
        this.bindings.set(item.shortcut, item.bindings);
      }
    }
    this.subscriptions.push(
      this.userModel.preferences$
        .pipe(
          map(preferences => preferences?.disable_keyboard_shortcuts ?? false),
          distinctUntilChanged()
        )
        .subscribe(x => (this.shortcutsDisabled = x))
    );
    this.keydownListener = (e: KeyboardEvent) => {
      if (!isComboKey(e.key)) return;
      if (this.shortcutsDisabled || shouldSuppress(e)) return;
      this.comboKeyLastPressed = {key: e.key, timestampMs: Date.now()};
    };
    document.addEventListener('keydown', this.keydownListener);
  }

  finalize() {
    document.removeEventListener('keydown', this.keydownListener);
    for (const s of this.subscriptions) {
      s.unsubscribe();
    }
  }

  public _testOnly_isEmpty() {
    return this.activeShortcuts.size === 0 && this.listeners.size === 0;
  }

  isInComboKeyMode() {
    return Object.values(ComboKey).some(key =>
      this.isInSpecificComboKeyMode(key)
    );
  }

  isInSpecificComboKeyMode(comboKey: ComboKey) {
    const {key, timestampMs} = this.comboKeyLastPressed;
    return (
      key === comboKey &&
      timestampMs &&
      Date.now() - timestampMs < COMBO_TIMEOUT_MS
    );
  }

  /**
   * TODO(brohlfs): Reconcile with the addShortcut() function in dom-util.
   * Most likely we will just keep this one here, but that is something for a
   * follow-up change.
   */
  addShortcut(
    element: HTMLElement,
    shortcut: Binding,
    listener: (e: KeyboardEvent) => void,
    options?: ShortcutOptions
  ) {
    const optShouldSuppress = options?.shouldSuppress ?? true;
    const optPreventDefault = options?.preventDefault ?? true;
    const wrappedListener = (e: KeyboardEvent) => {
      if (e.repeat && !shortcut.allowRepeat) return;
      if (!eventMatchesShortcut(e, shortcut)) return;
      if (shortcut.combo) {
        if (!this.isInSpecificComboKeyMode(shortcut.combo)) return;
      } else {
        if (this.isInComboKeyMode()) return;
      }
      if (optShouldSuppress && shouldSuppress(e)) return;
      // `shortcutsDisabled` refers to disabling global shortcuts like 'n'. If
      // `shouldSuppress` is false (e.g.for Ctrl - ENTER), then don't disable
      // the shortcut.
      if (optShouldSuppress && this.shortcutsDisabled) return;
      if (optPreventDefault) e.preventDefault();
      if (optPreventDefault) e.stopPropagation();
      this.reportTriggered(e);
      if (shortcut.combo) {
        // Do not reset immediately, otherwise other shortcut might be triggered.
        setTimeout(() => {
          this.comboKeyLastPressed = {};
        });
      }
      listener(e);
    };
    element.addEventListener('keydown', wrappedListener);
    return () => element.removeEventListener('keydown', wrappedListener);
  }

  private reportTriggered(e: KeyboardEvent) {
    // eg: {key: "k:keydown", ..., from: "gr-diff-view"}
    let key = `${e.key}:${e.type}`;
    if (this.isInSpecificComboKeyMode(ComboKey.G)) key = 'g+' + key;
    if (this.isInSpecificComboKeyMode(ComboKey.V)) key = 'v+' + key;
    if (e.shiftKey) key = 'shift+' + key;
    if (e.ctrlKey) key = 'ctrl+' + key;
    if (e.metaKey) key = 'meta+' + key;
    if (e.altKey) key = 'alt+' + key;
    let from = 'unknown';
    if (isElementTarget(e.currentTarget)) {
      from = e.currentTarget.tagName;
    }
    this.reporting?.reportInteraction('shortcut-triggered', {key, from});
  }

  createTitle(shortcutName: Shortcut, section: ShortcutSection) {
    const desc = this.getDescription(section, shortcutName);
    const shortcut = this.getShortcut(shortcutName);
    return desc && shortcut ? `${desc} (shortcut: ${shortcut})` : '';
  }

  getBindingsForShortcut(shortcut: Shortcut) {
    return this.bindings.get(shortcut);
  }

  /**
   * Looks up bindings for the given shortcut and calls addShortcut() for each
   * of them. Also adds the shortcut to `activeShortcuts` and thus to the
   * help page about active shortcuts. Returns a cleanup function for removing
   * the bindings and the help page entry.
   */
  addShortcutListener(
    shortcut: Shortcut,
    listener: (e: KeyboardEvent) => void,
    options?: ShortcutOptions
  ) {
    const cleanups: (() => void)[] = [];
    this.activeShortcuts.add(shortcut);
    cleanups.push(() => {
      this.activeShortcuts.delete(shortcut);
      this.notifyViewListeners();
    });
    const bindings = this.getBindingsForShortcut(shortcut);
    for (const binding of bindings ?? []) {
      if (binding.docOnly) continue;
      cleanups.push(
        this.addShortcut(document.body, binding, listener, options)
      );
    }
    this.notifyViewListeners();
    return () => {
      for (const cleanup of cleanups ?? []) cleanup();
    };
  }

  addListener(listener: ShortcutViewListener) {
    this.listeners.add(listener);
    listener(this.directoryView());
  }

  removeListener(listener: ShortcutViewListener) {
    return this.listeners.delete(listener);
  }

  getDescription(section: ShortcutSection, shortcutName: Shortcut) {
    const bindings = this.config.get(section);
    if (!bindings) return '';
    const binding = bindings.find(binding => binding.shortcut === shortcutName);
    return binding?.text ?? '';
  }

  getShortcut(shortcutName: Shortcut) {
    const bindings = this.bindings.get(shortcutName);
    if (!bindings) return '';
    return bindings
      .map(binding => describeBinding(binding).join('+'))
      .join(',');
  }

  activeShortcutsBySection() {
    const activeShortcutsBySection = new Map<
      ShortcutSection,
      ShortcutHelpItem[]
    >();
    this.config.forEach((shortcutList, section) => {
      shortcutList.forEach(shortcutHelp => {
        if (this.activeShortcuts.has(shortcutHelp.shortcut)) {
          if (!activeShortcutsBySection.has(section)) {
            activeShortcutsBySection.set(section, []);
          }
          activeShortcutsBySection.get(section)!.push(shortcutHelp);
        }
      });
    });
    return activeShortcutsBySection;
  }

  directoryView() {
    const view = new Map<ShortcutSection, SectionView>();
    this.activeShortcutsBySection().forEach((shortcutHelps, section) => {
      const sectionView: SectionView = [];
      shortcutHelps.forEach(shortcutHelp => {
        const bindingDesc = this.describeBindings(shortcutHelp.shortcut);
        if (!bindingDesc) {
          return;
        }
        this.distributeBindingDesc(bindingDesc).forEach(bindingDesc => {
          sectionView.push({
            binding: bindingDesc,
            text: shortcutHelp.text,
          });
        });
      });
      view.set(section, sectionView);
    });
    return view;
  }

  distributeBindingDesc(bindingDesc: string[][]): string[][][] {
    if (
      bindingDesc.length === 1 ||
      this.comboSetDisplayWidth(bindingDesc) < 21
    ) {
      return [bindingDesc];
    }
    // Find the largest prefix of bindings that is under the
    // size threshold.
    const head = [bindingDesc[0]];
    for (let i = 1; i < bindingDesc.length; i++) {
      head.push(bindingDesc[i]);
      if (this.comboSetDisplayWidth(head) >= 21) {
        head.pop();
        return [head].concat(this.distributeBindingDesc(bindingDesc.slice(i)));
      }
    }
    return [];
  }

  comboSetDisplayWidth(bindingDesc: string[][]) {
    const bindingSizer = (binding: string[]) =>
      binding.reduce((acc, key) => acc + key.length, 0);
    // Width is the sum of strings + (n-1) * 2 to account for the word
    // "or" joining them.
    return (
      bindingDesc.reduce((acc, binding) => acc + bindingSizer(binding), 0) +
      2 * (bindingDesc.length - 1)
    );
  }

  describeBindings(shortcut: Shortcut): string[][] | null {
    const bindings = this.bindings.get(shortcut);
    if (!bindings) return null;
    return bindings.map(binding => describeBinding(binding));
  }

  notifyViewListeners() {
    const view = this.directoryView();
    this.listeners.forEach(listener => listener(view));
  }
}

function describeKey(key: string | Key) {
  switch (key) {
    case Key.UP:
      return '\u2191'; // ↑
    case Key.DOWN:
      return '\u2193'; // ↓
    case Key.LEFT:
      return '\u2190'; // ←
    case Key.RIGHT:
      return '\u2192'; // →
    default:
      return key;
  }
}

export function describeBinding(binding: Binding): string[] {
  const description: string[] = [];
  if (binding.combo === ComboKey.G) {
    description.push('g');
  }
  if (binding.combo === ComboKey.V) {
    description.push('v');
  }
  if (
    binding.modifiers?.includes(Modifier.SHIFT_KEY) ||
    (isCharacterLetter(binding.key) && isUpperCase(binding.key))
  ) {
    description.push('Shift');
  }
  if (binding.modifiers?.includes(Modifier.ALT_KEY)) {
    description.push('Alt');
  }
  if (binding.modifiers?.includes(Modifier.CTRL_KEY)) {
    description.push('Ctrl');
  }
  if (binding.modifiers?.includes(Modifier.META_KEY)) {
    description.push('Meta/Cmd');
  }

  let key = describeKey(binding.key);
  if (isCharacterLetter(key)) {
    key = key.toLowerCase();
  }
  description.push(key);

  return description;
}
