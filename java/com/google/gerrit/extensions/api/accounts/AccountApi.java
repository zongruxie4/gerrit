// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.extensions.api.accounts;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.extensions.client.DiffPreferencesInfo;
import com.google.gerrit.extensions.client.EditPreferencesInfo;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo;
import com.google.gerrit.extensions.client.ProjectWatchInfo;
import com.google.gerrit.extensions.common.AccountDetailInfo;
import com.google.gerrit.extensions.common.AccountExternalIdInfo;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.AccountStateInfo;
import com.google.gerrit.extensions.common.AgreementInfo;
import com.google.gerrit.extensions.common.EmailInfo;
import com.google.gerrit.extensions.common.GpgKeyInfo;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.common.SshKeyInfo;
import com.google.gerrit.extensions.restapi.NotImplementedException;
import com.google.gerrit.extensions.restapi.RestApiException;
import java.util.List;
import java.util.Map;

public interface AccountApi {
  AccountInfo get() throws RestApiException;

  AccountDetailInfo detail() throws RestApiException;

  AccountStateInfo state() throws RestApiException;

  boolean getActive() throws RestApiException;

  void setActive(boolean active) throws RestApiException;

  String getAvatarUrl(int size) throws RestApiException;

  GeneralPreferencesInfo getPreferences() throws RestApiException;

  @CanIgnoreReturnValue
  GeneralPreferencesInfo setPreferences(GeneralPreferencesInfo in) throws RestApiException;

  DiffPreferencesInfo getDiffPreferences() throws RestApiException;

  @CanIgnoreReturnValue
  DiffPreferencesInfo setDiffPreferences(DiffPreferencesInfo in) throws RestApiException;

  EditPreferencesInfo getEditPreferences() throws RestApiException;

  @CanIgnoreReturnValue
  EditPreferencesInfo setEditPreferences(EditPreferencesInfo in) throws RestApiException;

  List<ProjectWatchInfo> getWatchedProjects() throws RestApiException;

  @CanIgnoreReturnValue
  List<ProjectWatchInfo> setWatchedProjects(List<ProjectWatchInfo> in) throws RestApiException;

  void deleteWatchedProjects(List<ProjectWatchInfo> in) throws RestApiException;

  void starChange(String changeId) throws RestApiException;

  void unstarChange(String changeId) throws RestApiException;

  List<GroupInfo> getGroups() throws RestApiException;

  List<EmailInfo> getEmails() throws RestApiException;

  void addEmail(EmailInput input) throws RestApiException;

  void deleteEmail(String email) throws RestApiException;

  @CanIgnoreReturnValue
  EmailApi createEmail(EmailInput emailInput) throws RestApiException;

  EmailApi email(String email) throws RestApiException;

  void setStatus(String status) throws RestApiException;

  void setDisplayName(String displayName) throws RestApiException;

  List<SshKeyInfo> listSshKeys() throws RestApiException;

  @CanIgnoreReturnValue
  SshKeyInfo addSshKey(String key) throws RestApiException;

  void deleteSshKey(int seq) throws RestApiException;

  Map<String, GpgKeyInfo> listGpgKeys() throws RestApiException;

  @CanIgnoreReturnValue
  Map<String, GpgKeyInfo> putGpgKeys(List<String> add, List<String> remove) throws RestApiException;

  GpgKeyApi gpgKey(String id) throws RestApiException;

  List<AgreementInfo> listAgreements() throws RestApiException;

  void signAgreement(String agreementName) throws RestApiException;

  void index() throws RestApiException;

  List<AccountExternalIdInfo> getExternalIds() throws RestApiException;

  void deleteExternalIds(List<String> externalIds) throws RestApiException;

  @CanIgnoreReturnValue
  List<DeletedDraftCommentInfo> deleteDraftComments(DeleteDraftCommentsInput input)
      throws RestApiException;

  void setName(String name) throws RestApiException;

  /**
   * Generate a new HTTP password.
   *
   * @return the generated password.
   */
  String generateHttpPassword() throws RestApiException;

  /**
   * Set a new HTTP password.
   *
   * <p>May only be invoked by administrators.
   *
   * @param httpPassword the new password, {@code null} to remove the password.
   * @return the new password, {@code null} if the password was removed.
   */
  @CanIgnoreReturnValue
  String setHttpPassword(String httpPassword) throws RestApiException;

  void delete() throws RestApiException;

  /**
   * A default implementation which allows source compatibility when adding new methods to the
   * interface.
   */
  class NotImplemented implements AccountApi {
    @Override
    public AccountInfo get() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public AccountDetailInfo detail() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public AccountStateInfo state() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public boolean getActive() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void setActive(boolean active) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public String getAvatarUrl(int size) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public GeneralPreferencesInfo getPreferences() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public GeneralPreferencesInfo setPreferences(GeneralPreferencesInfo in)
        throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public DiffPreferencesInfo getDiffPreferences() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public DiffPreferencesInfo setDiffPreferences(DiffPreferencesInfo in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public EditPreferencesInfo getEditPreferences() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public EditPreferencesInfo setEditPreferences(EditPreferencesInfo in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<ProjectWatchInfo> getWatchedProjects() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<ProjectWatchInfo> setWatchedProjects(List<ProjectWatchInfo> in)
        throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void deleteWatchedProjects(List<ProjectWatchInfo> in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void starChange(String changeId) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void unstarChange(String changeId) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<GroupInfo> getGroups() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<EmailInfo> getEmails() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void addEmail(EmailInput input) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void deleteEmail(String email) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public EmailApi createEmail(EmailInput input) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public EmailApi email(String email) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void setStatus(String status) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void setDisplayName(String displayName) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<SshKeyInfo> listSshKeys() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public SshKeyInfo addSshKey(String key) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void deleteSshKey(int seq) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public Map<String, GpgKeyInfo> putGpgKeys(List<String> add, List<String> remove)
        throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public GpgKeyApi gpgKey(String id) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public Map<String, GpgKeyInfo> listGpgKeys() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<AgreementInfo> listAgreements() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void signAgreement(String agreementName) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void index() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<AccountExternalIdInfo> getExternalIds() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void deleteExternalIds(List<String> externalIds) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public List<DeletedDraftCommentInfo> deleteDraftComments(DeleteDraftCommentsInput input)
        throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void setName(String name) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public String generateHttpPassword() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public String setHttpPassword(String httpPassword) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void delete() throws RestApiException {
      throw new NotImplementedException();
    }
  }
}
