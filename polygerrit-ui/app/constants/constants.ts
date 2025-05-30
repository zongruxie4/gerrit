/**
 * @license
 * Copyright 2020 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tab names for primary tabs on change view page.
 */
import {CopyInfoEventDetail, DiffViewMode, TextRange} from '../api/diff';
import {DiffPreferencesInfo} from '../types/diff';
import {EditPreferencesInfo, PreferencesInfo} from '../types/common';
import {
  AuthType,
  ChangeStatus,
  CommentSide,
  ConfigParameterInfoType,
  DefaultDisplayNameConfig,
  EditableAccountField,
  FileInfoStatus,
  GpgKeyInfoStatus,
  HttpMethod,
  InheritedBooleanInfoConfiguredValue,
  MergeabilityComputationBehavior,
  ProblemInfoStatus,
  RepoState,
  RequirementStatus,
  ReviewerState,
  RevisionKind,
  SubmitType,
} from '../api/rest-api';

export {
  AuthType,
  ChangeStatus,
  CommentSide,
  ConfigParameterInfoType,
  type CopyInfoEventDetail,
  DefaultDisplayNameConfig,
  EditableAccountField,
  FileInfoStatus,
  GpgKeyInfoStatus,
  HttpMethod,
  InheritedBooleanInfoConfiguredValue,
  MergeabilityComputationBehavior,
  ProblemInfoStatus,
  RepoState,
  RequirementStatus,
  ReviewerState,
  RevisionKind,
  SubmitType,
  type TextRange,
};

export enum AccountTag {
  SERVICE_USER = 'SERVICE_USER',
}

export enum Tab {
  FILES = 'files',
  /**
   * When renaming 'comments' UrlFormatter.java must be updated.
   */
  COMMENT_THREADS = 'comments',
  CHECKS = 'checks',
}

/**
 * Tag names of change log messages.
 */
export enum MessageTag {
  TAG_DELETE_REVIEWER = 'autogenerated:gerrit:deleteReviewer',
  TAG_NEW_PATCHSET = 'autogenerated:gerrit:newPatchSet',
  TAG_NEW_PATCHSET_OUTDATED_VOTES = 'autogenerated:gerrit:newPatchSetOutdatedVotes',
  TAG_NEW_WIP_PATCHSET = 'autogenerated:gerrit:newWipPatchSet',
  TAG_REVIEWER_UPDATE = 'autogenerated:gerrit:reviewerUpdate',
  TAG_SET_PRIVATE = 'autogenerated:gerrit:setPrivate',
  TAG_UNSET_PRIVATE = 'autogenerated:gerrit:unsetPrivate',
  TAG_SET_READY = 'autogenerated:gerrit:setReadyForReview',
  TAG_SET_WIP = 'autogenerated:gerrit:setWorkInProgress',
  TAG_MERGED = 'autogenerated:gerrit:merged',
  TAG_REVERT = 'autogenerated:gerrit:revert',
}

/**
 * @description These values are directly displayed in the dialog to show progress of
 * change.
 */
export enum ProgressStatus {
  RUNNING = 'RUNNING',
  FAILED = 'FAILED',
  NOT_STARTED = 'NOT STARTED',
  SUCCESSFUL = 'SUCCESSFUL',
}

export enum ColumnNames {
  SUBJECT = 'Subject',
  OWNER = 'Owner',
  REVIEWERS = 'Reviewers',
  REPO = 'Repo',
  BRANCH = 'Branch',
  UPDATED = 'Updated',
  SIZE = 'Size',
  STATUS = 'Status',
}

/**
 * @description Modes for gr-diff-cursor
 * The scroll behavior for the cursor. Values are 'never' and
 * 'keep-visible'. 'keep-visible' will only scroll if the cursor is beyond
 * the viewport.
 */
export enum ScrollMode {
  KEEP_VISIBLE = 'keep-visible',
  NEVER = 'never',
}

/**
 * Special file paths
 */
export enum SpecialFilePath {
  PATCHSET_LEVEL_COMMENTS = '/PATCHSET_LEVEL',
  COMMIT_MESSAGE = '/COMMIT_MSG',
  MERGE_LIST = '/MERGE_LIST',
}

export {Side} from '../api/diff';

/**
 * https://gerrit-review.googlesource.com/Documentation/rest-api-changes.html#mergeable-info
 */
export enum MergeStrategy {
  RECURSIVE = 'recursive',
  RESOLVE = 'resolve',
  SIMPLE_TWO_WAY_IN_CORE = 'simple-two-way-in-core',
  OURS = 'ours',
  THEIRS = 'theirs',
}

/**
 * Enum for possible PermissionRuleInfo actions
 * https://gerrit-review.googlesource.com/Documentation/rest-api-access.html#permission-info
 */
export enum PermissionAction {
  ALLOW = 'ALLOW',
  DENY = 'DENY',
  BLOCK = 'BLOCK',
  // Special values for global capabilities
  INTERACTIVE = 'INTERACTIVE',
  BATCH = 'BATCH',
}

/**
 * This capability allows users to use the thread pool reserved for 'Non-Interactive Users'.
 * https://gerrit-review.googlesource.com/Documentation/access-control.html#capability_priority
 */
export enum UserPriority {
  BATCH = 'BATCH',
  INTERACTIVE = 'INTERACTIVE',
}

/**
 * Allowed app themes
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum AppTheme {
  AUTO = 'AUTO',
  DARK = 'DARK',
  LIGHT = 'LIGHT',
}

/**
 * Date formats in preferences
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum DateFormat {
  STD = 'STD',
  US = 'US',
  ISO = 'ISO',
  EURO = 'EURO',
  UK = 'UK',
}

/**
 * Time formats in preferences
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum TimeFormat {
  HHMM_12 = 'HHMM_12',
  HHMM_24 = 'HHMM_24',
}

export {DiffViewMode};

/**
 * The type of email strategy to use.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum EmailStrategy {
  ENABLED = 'ENABLED',
  CC_ON_OWN_COMMENTS = 'CC_ON_OWN_COMMENTS',
  ATTENTION_SET_ONLY = 'ATTENTION_SET_ONLY',
  DISABLED = 'DISABLED',
}

/**
 * The type of email format to use.
 * Doesn't mentioned in doc, but exists in Java class GeneralPreferencesInfo.
 */

export enum EmailFormat {
  PLAINTEXT = 'PLAINTEXT',
  HTML_PLAINTEXT = 'HTML_PLAINTEXT',
}

/**
 * The base which should be pre-selected in the 'Diff Against' drop-down list when the change screen is opened for a merge commit
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum DefaultBase {
  AUTO_MERGE = 'AUTO_MERGE',
  FIRST_PARENT = 'FIRST_PARENT',
}

/**
 * how draft comments are handled
 */
export enum DraftsAction {
  PUBLISH = 'PUBLISH',
  PUBLISH_ALL_REVISIONS = 'PUBLISH_ALL_REVISIONS',
  KEEP = 'KEEP',
}

export enum NotifyType {
  NONE = 'NONE',
  OWNER = 'OWNER',
  OWNER_REVIEWERS = 'OWNER_REVIEWERS',
  ALL = 'ALL',
}

/**
 * Controls visibility of other users' dashboard pages and completion suggestions to web users
 * https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#accounts.visibility
 */
export enum AccountsVisibility {
  ALL = 'ALL',
  SAME_GROUP = 'SAME_GROUP',
  VISIBLE_GROUP = 'VISIBLE_GROUP',
  NONE = 'NONE',
}

// These defaults should match the defaults in
// java/com/google/gerrit/extensions/client/GeneralPreferencesInfo.java
export function createDefaultPreferences(): PreferencesInfo {
  return {
    changes_per_page: 25,
    diff_view: DiffViewMode.SIDE_BY_SIDE,
    size_bar_in_change_table: true,
    my: [],
    theme: AppTheme.AUTO,
    date_format: DateFormat.STD,
    time_format: TimeFormat.HHMM_12,
    change_table: [],
    email_strategy: EmailStrategy.ATTENTION_SET_ONLY,
    default_base_for_merges: DefaultBase.AUTO_MERGE,
    allow_browser_notifications: false,
    allow_suggest_code_while_commenting: true,
    allow_autocompleting_comments: true,
    diff_page_sidebar: 'NONE',
  };
}

// These defaults should match the defaults in
// java/com/google/gerrit/extensions/client/DiffPreferencesInfo.java
// NOTE: There are some settings that don't apply to PolyGerrit
// (Render mode being at least one of them).
export function createDefaultDiffPrefs(): DiffPreferencesInfo {
  return {
    context: 10,
    cursor_blink_rate: 0,
    font_size: 12,
    ignore_whitespace: 'IGNORE_NONE',
    line_length: 100,
    line_wrapping: false,
    show_line_endings: true,
    show_tabs: true,
    show_whitespace_errors: true,
    syntax_highlighting: true,
    tab_size: 8,
  };
}

// These defaults should match the defaults in
// java/com/google/gerrit/extensions/client/EditPreferencesInfo.java
export function createDefaultEditPrefs(): EditPreferencesInfo {
  return {
    auto_close_brackets: false,
    cursor_blink_rate: 0,
    hide_line_numbers: false,
    hide_top_menu: false,
    indent_unit: 2,
    indent_with_tabs: false,
    line_length: 100,
    line_wrapping: false,
    match_brackets: true,
    show_base: false,
    show_tabs: true,
    show_whitespace_errors: true,
    syntax_highlighting: true,
    tab_size: 8,
  };
}

export const RELOAD_DASHBOARD_INTERVAL_MS = 10 * 1000;

export const WAITING = 'Waiting';
