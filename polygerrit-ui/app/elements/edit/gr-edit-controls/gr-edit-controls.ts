/**
 * @license
 * Copyright 2017 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '@polymer/iron-input/iron-input';
import '../../shared/gr-autocomplete/gr-autocomplete';
import '../../shared/gr-button/gr-button';
import '../../shared/gr-dialog/gr-dialog';
import {GrEditAction, GrEditConstants} from '../gr-edit-constants';
import {navigationToken} from '../../core/gr-navigation/gr-navigation';
import {
  ChangeInfo,
  PARENT,
  PatchSetNum,
  RevisionPatchSetNum,
} from '../../../types/common';
import {GrDialog} from '../../shared/gr-dialog/gr-dialog';
import {
  AutocompleteQuery,
  AutocompleteSuggestion,
  GrAutocomplete,
} from '../../shared/gr-autocomplete/gr-autocomplete';
import {getAppContext} from '../../../services/app-context';
import {fireAlert} from '../../../utils/event-util';
import {
  assertIsDefined,
  queryAll,
  query as queryUtil,
} from '../../../utils/common-util';
import {sharedStyles} from '../../../styles/shared-styles';
import {css, html, LitElement} from 'lit';
import {customElement, property, query, state} from 'lit/decorators.js';
import {BindValueChangeEvent} from '../../../types/events';
import {IronInputElement} from '@polymer/iron-input/iron-input';
import {changeViewModelToken} from '../../../models/views/change';
import {resolve} from '../../../models/dependency';
import {modalStyles} from '../../../styles/gr-modal-styles';
import {whenVisible} from '../../../utils/dom-util';
import {throwingErrorCallback} from '../../shared/gr-rest-api-interface/gr-rest-apis/gr-rest-api-helper';
import {changeModelToken} from '../../../models/change/change-model';
import {formStyles} from '../../../styles/form-styles';
import {spinnerStyles} from '../../../styles/gr-spinner-styles';
import {formatBytes} from '../../../utils/file-util';

const FILE_UPLOAD_FAILURE = 'File failed to upload.';

@customElement('gr-edit-controls')
export class GrEditControls extends LitElement {
  // private but used in test
  @query('#newPathIronInput') newPathIronInput?: IronInputElement;

  @query('#modal') modal?: HTMLDialogElement;

  // private but used in test
  @query('#openDialog') openDialog?: GrDialog;

  // private but used in test
  @query('#deleteDialog') deleteDialog?: GrDialog;

  // private but used in test
  @query('#renameDialog') renameDialog?: GrDialog;

  // private but used in test
  @query('#restoreDialog') restoreDialog?: GrDialog;

  // private but used in test
  @query('#dragDropArea') dragDropArea?: HTMLDivElement;

  @query('#fileUploadInput') private fileUploadInput?: HTMLInputElement;

  @property({type: Object})
  change?: ChangeInfo;

  @property({type: String})
  patchNum?: PatchSetNum;

  @property({type: Array})
  hiddenActions: string[] = [GrEditConstants.Actions.RESTORE.id];

  // private but used in test
  @state() actions: GrEditAction[] = Object.values(GrEditConstants.Actions);

  // private but used in test
  @state() path = '';

  // private but used in test
  @state() newPath = '';

  @state() private fileName?: string;

  @state() private fileSize?: string;

  @state() fileUploaded?: boolean;

  private readonly query: AutocompleteQuery = (input: string) =>
    this.queryFiles(input);

  private readonly restApiService = getAppContext().restApiService;

  private readonly getChangeModel = resolve(this, changeModelToken);

  private readonly getViewModel = resolve(this, changeViewModelToken);

  private readonly getNavigation = resolve(this, navigationToken);

  static override get styles() {
    return [
      formStyles,
      sharedStyles,
      modalStyles,
      spinnerStyles,
      css`
        :host {
          align-items: center;
          display: flex;
          justify-content: flex-end;
        }
        .invisible {
          display: none;
        }
        gr-button {
          margin-left: var(--spacing-l);
          text-decoration: none;
        }
        gr-dialog {
          width: calc(min(50em, 90vw));
        }
        gr-dialog .main {
          width: 100%;
        }
        gr-dialog .main > iron-input {
          width: 100%;
        }
        input {
          border: 1px solid var(--border-color);
          border-radius: var(--border-radius);
          margin: var(--spacing-m) 0;
          padding: var(--spacing-s);
          width: 100%;
          box-sizing: border-box;
        }
        #dragDropArea {
          border: 2px dashed var(--border-color);
          border-radius: var(--border-radius);
          margin-top: var(--spacing-l);
          padding: var(--spacing-xxl) var(--spacing-xxl);
          text-align: center;
          cursor: pointer;
        }
        #dragDropArea:hover,
        .hover {
          background-color: var(--hover-background-color);
        }
        #dragDropArea > p {
          font-weight: var(--font-weight-medium);
          padding: var(--spacing-s);
        }
        .loadingSpin {
          width: calc(var(--line-height-normal) - 2px);
          height: calc(var(--line-height-normal) - 2px);
          display: inline-block;
          vertical-align: middle;
        }
        .fileUploadInfo {
          margin-top: var(--spacing-l);
        }
        .disabled {
          pointer-events: none;
          opacity: 0.6;
        }
        @media screen and (max-width: 50em) {
          gr-dialog {
            height: 90vh;
          }
        }
      `,
    ];
  }

  override render() {
    return html`
      ${this.actions.map(action => this.renderAction(action))}
      <dialog id="modal" tabindex="-1">
        ${this.renderOpenDialog()} ${this.renderDeleteDialog()}
        ${this.renderRenameDialog()} ${this.renderRestoreDialog()}
      </dialog>
    `;
  }

  private renderAction(action: GrEditAction) {
    return html`
      <gr-button
        id=${action.id}
        class=${this.computeIsInvisible(action.id)}
        link=""
        @click=${this.handleClick}
        >${action.label}</gr-button
      >
    `;
  }

  private renderOpenDialog() {
    return html`
      <gr-dialog
        id="openDialog"
        class="invisible dialog"
        ?disabled=${!this.isValidPath(this.path) || !!this.fileUploaded}
        ?disableCancel=${!!this.fileUploaded}
        confirm-label="Confirm"
        confirm-on-enter=""
        @confirm=${(e: Event) => this.handleOpenConfirm(e)}
        @cancel=${(e: Event) => this.handleDialogCancel(e)}
      >
        <div class="header" slot="header">
          Add a new file or open an existing file
        </div>
        <div class="main" slot="main">
          <gr-autocomplete
            placeholder="Enter an existing or new full file path."
            .query=${this.query}
            .text=${this.path}
            @text-changed=${(e: BindValueChangeEvent) =>
              this.handleTextChanged(e)}
          ></gr-autocomplete>
          <!-- We have to call preventDefault for dragenter and dragover
                in order for the drop event to work. -->
          <div
            id="dragDropArea"
            @drop=${(e: DragEvent) => this.handleDragAndDropUpload(e)}
            @click=${() => {
              if (!this.fileUploaded) {
                this.fileUploadInput?.click();
              }
            }}
            @dragenter=${(e: DragEvent) => e.preventDefault()}
            @dragover=${(e: DragEvent) => {
              e.preventDefault();
              if (!this.fileUploaded && this.dragDropArea) {
                this.dragDropArea.classList.add('hover');
              }
            }}
            @dragleave=${() => {
              if (!this.fileUploaded && this.dragDropArea) {
                this.dragDropArea.classList.remove('hover');
              }
            }}
          >
            <p>Drag and drop your file here, or click to select</p>
            <input
              id="fileUploadInput"
              type="file"
              @change=${(e: InputEvent) => this.handleFileUploadChange(e)}
              ?hidden=${true}
            />
          </div>
          ${this.renderLoading()}
        </div>
      </gr-dialog>
    `;
  }

  private renderLoading() {
    if (!this.fileUploaded) return;
    return html`
      <div id="fileUploadInfo" class="fileUploadInfo">
        <span class="loadingSpin"></span>
        <span>Uploading...</span>
        <span>${this.fileName} (${this.fileSize})</span>
      </div>
    `;
  }

  private renderDeleteDialog() {
    return html`
      <gr-dialog
        id="deleteDialog"
        class="invisible dialog"
        ?disabled=${!this.isValidPath(this.path)}
        confirm-label="Delete"
        confirm-on-enter=""
        @confirm=${(e: Event) => this.handleDeleteConfirm(e)}
        @cancel=${(e: Event) => this.handleDialogCancel(e)}
      >
        <div class="header" slot="header">Delete a file from the repo</div>
        <div class="main" slot="main">
          <gr-autocomplete
            placeholder="Enter an existing full file path."
            .query=${this.query}
            .text=${this.path}
            @text-changed=${(e: BindValueChangeEvent) =>
              this.handleTextChanged(e)}
          ></gr-autocomplete>
        </div>
      </gr-dialog>
    `;
  }

  private renderRenameDialog() {
    return html`
      <gr-dialog
        id="renameDialog"
        class="invisible dialog"
        ?disabled=${!this.isValidPath(this.path) ||
        !this.isValidPath(this.newPath)}
        confirm-label="Rename"
        confirm-on-enter=""
        @confirm=${(e: Event) => this.handleRenameConfirm(e)}
        @cancel=${(e: Event) => this.handleDialogCancel(e)}
      >
        <div class="header" slot="header">Rename a file in the repo</div>
        <div class="main" slot="main">
          <gr-autocomplete
            placeholder="Enter an existing full file path."
            .query=${this.query}
            .text=${this.path}
            @text-changed=${(e: BindValueChangeEvent) =>
              this.handleTextChanged(e)}
          ></gr-autocomplete>
          <iron-input
            id="newPathIronInput"
            .bindValue=${this.newPath}
            @bind-value-changed=${(e: BindValueChangeEvent) =>
              this.handleBindValueChangedNewPath(e)}
          >
            <input id="newPathInput" placeholder="Enter the new path." />
          </iron-input>
        </div>
      </gr-dialog>
    `;
  }

  private renderRestoreDialog() {
    return html`
      <gr-dialog
        id="restoreDialog"
        class="invisible dialog"
        confirm-label="Restore"
        confirm-on-enter=""
        @confirm=${(e: Event) => this.handleRestoreConfirm(e)}
        @cancel=${(e: Event) => this.handleDialogCancel(e)}
      >
        <div class="header" slot="header">Restore this file?</div>
        <div class="main" slot="main">
          <iron-input
            .bindValue=${this.path}
            @bind-value-changed=${(e: BindValueChangeEvent) =>
              this.handleBindValueChangedPath(e)}
          >
            <input disabled />
          </iron-input>
        </div>
      </gr-dialog>
    `;
  }

  private readonly handleClick = (e: Event) => {
    e.preventDefault();
    const target = e.target as Element;
    const action = target.id;
    switch (action) {
      case GrEditConstants.Actions.OPEN.id:
        this.openOpenDialog();
        return;
      case GrEditConstants.Actions.DELETE.id:
        this.openDeleteDialog();
        return;
      case GrEditConstants.Actions.RENAME.id:
        this.openRenameDialog();
        return;
      case GrEditConstants.Actions.RESTORE.id:
        this.openRestoreDialog();
        return;
    }
  };

  openOpenDialog(path?: string) {
    if (path) {
      this.path = path;
    }
    this.resetUploadStatus();
    assertIsDefined(this.openDialog, 'openDialog');
    this.showDialog(this.openDialog);
  }

  openDeleteDialog(path?: string) {
    if (path) {
      this.path = path;
    }
    assertIsDefined(this.deleteDialog, 'deleteDialog');
    this.showDialog(this.deleteDialog);
  }

  openRenameDialog(path?: string) {
    if (path) {
      this.path = path;
    }
    assertIsDefined(this.renameDialog, 'renameDialog');
    this.showDialog(this.renameDialog);
  }

  openRestoreDialog(path?: string) {
    assertIsDefined(this.restoreDialog, 'restoreDialog');
    if (path) {
      this.path = path;
    }
    this.showDialog(this.restoreDialog);
  }

  /**
   * Given a path string, checks that it is a valid file path.
   *
   * Private but used in test
   */
  isValidPath(path: string) {
    // Double negation needed for strict boolean return type.
    return !!path.length && !path.endsWith('/');
  }

  /**
   * Given a dom event, gets the dialog that lies along this event path.
   *
   * Private but used in test
   */
  getDialogFromEvent(e: Event): GrDialog | undefined {
    return e.composedPath().find(element => {
      if (!(element instanceof Element)) return false;
      if (!element.classList) return false;
      return element.classList.contains('dialog');
    }) as GrDialog | undefined;
  }

  // Private but used in test
  showDialog(dialog: GrDialog) {
    assertIsDefined(this.modal, 'modal');

    // Some dialogs may not fire their on-close event when closed in certain
    // ways (e.g. by clicking outside the dialog body). This call prevents
    // multiple dialogs from being shown in the same modal.
    this.hideAllDialogs();

    this.modal.showModal();
    whenVisible(this.modal, () => {
      dialog.classList.toggle('invisible', false);
      const autocomplete = queryUtil<GrAutocomplete>(dialog, 'gr-autocomplete');
      if (autocomplete) {
        autocomplete.focus();
      }
    });
  }

  // Private but used in test
  hideAllDialogs() {
    const dialogs = queryAll<GrDialog>(this, '.dialog');
    for (const dialog of dialogs) {
      // We set the second param to false, because this function
      // is called by showDialog which when you open either restore,
      // delete or rename dialogs, it reseted the automatically
      // set input.
      this.closeDialog(dialog, false);
    }
  }

  // Private but used in test
  closeDialog(dialog?: GrDialog, clearInputs = true) {
    if (!dialog) return;

    if (clearInputs) {
      // Dialog may have autocompletes and plain inputs -- as these have
      // different properties representing their bound text, it is easier to
      // just make two separate queries.
      dialog.querySelectorAll('gr-autocomplete').forEach(input => {
        input.text = '';
      });

      dialog.querySelectorAll('iron-input').forEach(input => {
        input.bindValue = '';
      });
    }

    dialog.classList.toggle('invisible', true);

    assertIsDefined(this.modal, 'modal');
    this.modal.close();
  }

  private handleDialogCancel(e: Event) {
    this.closeDialog(this.getDialogFromEvent(e));
  }

  private handleOpenConfirm(e: Event) {
    if (!this.change || !this.path) {
      fireAlert(this, 'You must enter a path.');
      this.closeDialog(this.openDialog);
      return;
    }
    const patchNum = this.patchNum;
    assertIsDefined(this.patchNum, 'patchset number');
    if (patchNum === PARENT) {
      fireAlert(this, "This doesn't work on Parent");
    }
    const url = this.getViewModel().editUrl({
      editView: {path: this.path},
      // since parent is checked above, it's revision patchset.
      patchNum: patchNum as RevisionPatchSetNum,
    });
    this.getNavigation().setUrl(url);
    this.closeDialog(this.getDialogFromEvent(e));
  }

  // Private but used in test
  handleUploadConfirm(path: string, fileData: string) {
    if (!this.change || !path || !fileData) {
      fireAlert(this, 'You must enter a path and data.');
      this.resetUploadStatus();
      this.closeDialog(this.openDialog);
      return Promise.resolve();
    }
    return this.restApiService
      .saveFileUploadChangeEdit(this.change._number, path, fileData)
      .then(res => {
        if (!res || !res.ok) {
          fireAlert(this, FILE_UPLOAD_FAILURE);
        }
        this.resetUploadStatus();
        this.closeDialog(this.openDialog);
        this.getChangeModel().navigateToChangeResetReload();
      })
      .catch(() => {
        fireAlert(this, FILE_UPLOAD_FAILURE);
        this.resetUploadStatus();
        this.closeDialog(this.openDialog);
      });
  }

  private handleDeleteConfirm(e: Event) {
    // Get the dialog before the api call as the event will change during bubbling
    // which will make Polymer.dom(e).path an empty array in polymer 2
    const dialog = this.getDialogFromEvent(e);
    if (!this.change || !this.path) {
      fireAlert(this, 'You must enter a path.');
      this.closeDialog(dialog);
      return;
    }
    this.restApiService
      .deleteFileInChangeEdit(this.change._number, this.path)
      .then(res => {
        if (!res || !res.ok) {
          return;
        }
        this.closeDialog(dialog);
        this.getChangeModel().navigateToChangeResetReload();
      });
  }

  private handleRestoreConfirm(e: Event) {
    const dialog = this.getDialogFromEvent(e);
    if (!this.change || !this.path) {
      fireAlert(this, 'You must enter a path.');
      this.closeDialog(dialog);
      return;
    }
    this.restApiService
      .restoreFileInChangeEdit(this.change._number, this.path)
      .then(res => {
        if (!res || !res.ok) {
          return;
        }
        this.closeDialog(dialog);
        this.getChangeModel().navigateToChangeResetReload();
      });
  }

  private handleRenameConfirm(e: Event) {
    const dialog = this.getDialogFromEvent(e);
    if (!this.change || !this.path || !this.newPath) {
      fireAlert(this, 'You must enter a old path and a new path.');
      this.closeDialog(dialog);
      return;
    }
    this.restApiService
      .renameFileInChangeEdit(this.change._number, this.path, this.newPath)
      .then(res => {
        if (!res || !res.ok) {
          return;
        }
        this.closeDialog(dialog);
        this.getChangeModel().navigateToChangeResetReload();
      });
  }

  private queryFiles(input: string): Promise<AutocompleteSuggestion[]> {
    assertIsDefined(this.change, 'this.change');
    assertIsDefined(this.patchNum, 'this.patchNum');
    return this.restApiService
      .queryChangeFiles(
        this.change._number,
        this.patchNum,
        input,
        throwingErrorCallback
      )
      .then(res => {
        if (!res)
          throw new Error('Failed to retrieve files. Response not set.');
        return res.map(file => {
          return {name: file};
        });
      });
  }

  private computeIsInvisible(id: string) {
    return this.hiddenActions.includes(id) ? 'invisible' : '';
  }

  private handleDragAndDropUpload(e: DragEvent) {
    if (this.fileUploaded) return;

    e.preventDefault();
    e.stopPropagation();

    // Reset background on drop
    this.dragDropArea!.classList.remove('hover');

    if (!e.dataTransfer || !e.dataTransfer.files) return;
    this.fileUpload(e.dataTransfer.files[0]);
  }

  private handleFileUploadChange(e: InputEvent) {
    const input = e.target;
    if (
      this.fileUploaded ||
      !input ||
      !(input instanceof HTMLInputElement) ||
      !input.files
    )
      return;
    this.fileUpload(input.files[0]);
  }

  private async fileUpload(file: File) {
    if (!file) return;

    let path = this.path;
    if (!path) {
      path = file.name;
    }

    this.fileUploaded = true;
    this.fileName = path;
    this.fileSize = formatBytes(file.size, false);
    await this.updateComplete;

    // Disable drag/drop and input during the upload process
    this.dragDropArea?.classList.add('disabled');

    const fileReader = new FileReader();
    fileReader.onload = (fileLoadEvent: ProgressEvent<FileReader>) => {
      if (!fileLoadEvent) return;
      const fileData = fileLoadEvent.target!.result;
      if (typeof fileData !== 'string') return;
      this.handleUploadConfirm(path, fileData);
    };
    fileReader.readAsDataURL(file);
  }

  private handleTextChanged(e: BindValueChangeEvent) {
    this.path = e.detail.value ?? '';
  }

  private handleBindValueChangedNewPath(e: BindValueChangeEvent) {
    this.newPath = e.detail.value ?? '';
  }

  private handleBindValueChangedPath(e: BindValueChangeEvent) {
    this.path = e.detail.value ?? '';
  }

  // Reset the upload status when dialog is opened or closed
  private async resetUploadStatus() {
    this.fileName = '';
    this.fileSize = '';
    const dropZone = this.dragDropArea;
    dropZone!.classList.remove('disabled');
    dropZone!.classList.remove('hover');
    this.fileUploaded = false;
    await this.updateComplete;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'gr-edit-controls': GrEditControls;
  }
}
