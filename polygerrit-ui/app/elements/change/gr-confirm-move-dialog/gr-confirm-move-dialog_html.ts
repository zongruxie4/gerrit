/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import {html} from '@polymer/polymer/lib/utils/html-tag';

export const htmlTemplate = html`
  <style include="shared-styles">
    :host {
      display: block;
      width: 30em;
    }
    :host([disabled]) {
      opacity: 0.5;
      pointer-events: none;
    }
    label {
      cursor: pointer;
    }
    .main {
      display: flex;
      flex-direction: column;
      width: 100%;
    }
    .main label,
    .main input[type='text'] {
      display: block;
      width: 100%;
    }
    .main .message {
      width: 100%;
    }
    .warning {
      color: var(--error-text-color);
    }
  </style>
  <gr-dialog
    confirm-label="Move Change"
    on-confirm="_handleConfirmTap"
    on-cancel="_handleCancelTap"
  >
    <div class="header" slot="header">Move Change to Another Branch</div>
    <div class="main" slot="main">
      <p class="warning">
        Warning: moving a change will not change its parents.
      </p>
      <label for="branchInput">
        Move change to branch
      </label>
      <gr-autocomplete
        id="branchInput"
        text="{{branch}}"
        query="[[_query]]"
        placeholder="Destination branch"
      >
      </gr-autocomplete>
      <label for="messageInput">
        Move Change Message
      </label>
      <iron-autogrow-textarea
        id="messageInput"
        class="message"
        autocomplete="on"
        rows="4"
        max-rows="15"
        bind-value="{{message}}"
      ></iron-autogrow-textarea>
    </div>
  </gr-dialog>
`;
