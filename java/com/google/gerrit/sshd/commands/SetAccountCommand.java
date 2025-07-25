// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.sshd.commands;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.entities.Account;
import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.extensions.api.accounts.EmailInput;
import com.google.gerrit.extensions.api.accounts.SshKeyInput;
import com.google.gerrit.extensions.auth.AuthTokenInfo;
import com.google.gerrit.extensions.auth.AuthTokenInput;
import com.google.gerrit.extensions.common.EmailInfo;
import com.google.gerrit.extensions.common.HttpPasswordInput;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.common.NameInput;
import com.google.gerrit.extensions.common.SshKeyInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.account.AccountSshKey;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.restapi.account.AddSshKey;
import com.google.gerrit.server.restapi.account.CreateEmail;
import com.google.gerrit.server.restapi.account.CreateToken;
import com.google.gerrit.server.restapi.account.DeleteActive;
import com.google.gerrit.server.restapi.account.DeleteEmail;
import com.google.gerrit.server.restapi.account.DeleteExternalIds;
import com.google.gerrit.server.restapi.account.DeleteSshKey;
import com.google.gerrit.server.restapi.account.DeleteToken;
import com.google.gerrit.server.restapi.account.GetEmails;
import com.google.gerrit.server.restapi.account.GetSshKeys;
import com.google.gerrit.server.restapi.account.PutActive;
import com.google.gerrit.server.restapi.account.PutHttpPassword;
import com.google.gerrit.server.restapi.account.PutName;
import com.google.gerrit.server.restapi.account.PutPreferred;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** Set a user's account settings. * */
@CommandMetaData(name = "set-account", description = "Change an account's settings")
final class SetAccountCommand extends SshCommand {

  @Argument(
      index = 0,
      required = true,
      metaVar = "USER",
      usage = "full name, email-address, ssh username or account id")
  private Account.Id id;

  @Option(name = "--full-name", metaVar = "NAME", usage = "display name of the account")
  private String fullName;

  @Option(name = "--active", usage = "set account's state to active")
  private boolean active;

  @Option(name = "--inactive", usage = "set account's state to inactive")
  private boolean inactive;

  @Option(name = "--add-email", metaVar = "EMAIL", usage = "email addresses to add to the account")
  private List<String> addEmails = new ArrayList<>();

  @Option(
      name = "--delete-email",
      metaVar = "EMAIL",
      usage = "email addresses to delete from the account")
  private List<String> deleteEmails = new ArrayList<>();

  @Option(
      name = "--preferred-email",
      metaVar = "EMAIL",
      usage = "a registered email address from the account")
  private String preferredEmail;

  @Option(name = "--add-ssh-key", metaVar = "-|KEY", usage = "public keys to add to the account")
  private List<String> addSshKeys = new ArrayList<>();

  @Option(
      name = "--delete-ssh-key",
      metaVar = "-|KEY",
      usage = "public keys to delete from the account")
  private List<String> deleteSshKeys = new ArrayList<>();

  @Option(
      name = "--http-password",
      metaVar = "PASSWORD",
      usage = "password for HTTP authentication for the account")
  private String httpPassword;

  @Option(name = "--clear-http-password", usage = "clear HTTP password for the account")
  private boolean clearHttpPassword;

  @Option(name = "--generate-http-password", usage = "generate a new HTTP password for the account")
  private boolean generateHttpPassword;

  @Option(name = "--token", metaVar = "TOKEN", usage = "token for HTTP authentication")
  private List<String> tokens = new ArrayList<>();

  @Option(
      name = "--generate-token",
      metaVar = "TOKENID",
      usage = "generate a new token for the account")
  private List<String> tokenIdsToGenerate = new ArrayList<>();

  @Option(
      name = "--delete-token",
      metaVar = "TOKENID",
      usage = "delete token with given ID for the account")
  private List<String> tokenIdsToDelete = new ArrayList<>();

  @Option(
      name = "--delete-external-id",
      metaVar = "EXTERNALID",
      usage = "external id to delete from the account")
  private List<String> externalIdsToDelete = new ArrayList<>();

  @Inject private IdentifiedUser.GenericFactory genericUserFactory;

  @Inject private CreateEmail createEmail;

  @Inject private DeleteExternalIds deleteExternalIds;

  @Inject private GetEmails getEmails;

  @Inject private DeleteEmail deleteEmail;

  @Inject private PutPreferred putPreferred;

  @Inject private PutName putName;

  @Inject private PutHttpPassword putHttpPassword;

  @Inject private CreateToken createToken;

  @Inject private DeleteToken deleteToken;

  @Inject private PutActive putActive;

  @Inject private DeleteActive deleteActive;

  @Inject private AddSshKey addSshKey;

  @Inject private GetSshKeys getSshKeys;

  @Inject private DeleteSshKey deleteSshKey;

  @Inject private PermissionBackend permissionBackend;

  @Inject private Provider<CurrentUser> userProvider;

  @Inject private ExternalIds externalIds;

  private AccountResource rsrc;

  @SuppressWarnings("UnnecessaryAssignment")
  @Override
  public void run() throws Exception {
    enableGracefulStop();
    user = genericUserFactory.create(id);

    validate();
    setAccount();
  }

  private void validate() throws UnloggedFailure {
    PermissionBackend.WithUser userPermission = permissionBackend.user(userProvider.get());

    boolean isAdmin = userPermission.testOrFalse(GlobalPermission.ADMINISTRATE_SERVER);
    boolean canModifyAccount =
        isAdmin || userPermission.testOrFalse(GlobalPermission.MODIFY_ACCOUNT);

    if (!user.hasSameAccountId(userProvider.get()) && !canModifyAccount) {
      throw die(
          "Setting another user's account information requries 'modify account' or 'administrate"
              + " server' capabilities.");
    }
    if (active || inactive) {
      if (!canModifyAccount) {
        throw die(
            "--active and --inactive require 'modify account' or 'administrate server'"
                + " capabilities.");
      }
      if (active && inactive) {
        throw die("--active and --inactive options are mutually exclusive.");
      }
    }

    if (generateHttpPassword && clearHttpPassword) {
      throw die("--generate-http-password and --clear-http-password are mutually exclusive.");
    }
    if (!Strings.isNullOrEmpty(httpPassword)) { // gave --http-password
      if (!isAdmin) {
        throw die("--http-password requires 'administrate server' capabilities.");
      }
      if (generateHttpPassword) {
        throw die("--http-password and --generate-http-password options are mutually exclusive.");
      }
      if (clearHttpPassword) {
        throw die("--http-password and --clear-http-password options are mutually exclusive.");
      }
    }
    if (addSshKeys.contains("-") && deleteSshKeys.contains("-")) {
      throw die("Only one option may use the stdin");
    }
    if (deleteSshKeys.contains("ALL")) {
      deleteSshKeys = Collections.singletonList("ALL");
    }
    if (deleteEmails.contains("ALL")) {
      deleteEmails = Collections.singletonList("ALL");
    }
    if (deleteEmails.contains(preferredEmail)) {
      throw die(
          "--preferred-email and --delete-email options are mutually "
              + "exclusive for the same email address.");
    }
    if (externalIdsToDelete.contains("ALL")) {
      externalIdsToDelete = Collections.singletonList("ALL");
    }
  }

  private void setAccount() throws Failure {
    rsrc = new AccountResource(user.asIdentifiedUser());
    try {
      for (String email : addEmails) {
        addEmail(email);
      }

      for (String email : deleteEmails) {
        deleteEmail(email);
      }

      if (preferredEmail != null) {
        putPreferred(preferredEmail);
      }

      if (fullName != null) {
        NameInput in = new NameInput();
        in.name = fullName;
        @SuppressWarnings("unused")
        var unused = putName.apply(rsrc, in);
      }

      if (httpPassword != null || clearHttpPassword || generateHttpPassword) {
        HttpPasswordInput in = new HttpPasswordInput();
        in.httpPassword = httpPassword;
        if (generateHttpPassword) {
          in.generate = true;
        }
        Response<String> resp = putHttpPassword.apply(rsrc, in);
        if (generateHttpPassword) {
          stdout.print("New password: " + resp.value() + "\n");
        }
      }

      for (String token : tokens) {
        AuthTokenInput tokenInput = new AuthTokenInput();
        tokenInput.id = String.format("token_%d", Instant.now().toEpochMilli());
        tokenInput.token = token;
        createToken.apply(rsrc, IdString.fromDecoded(tokenInput.id), tokenInput);
      }

      for (String tokenId : tokenIdsToGenerate) {
        AuthTokenInput tokenInput = new AuthTokenInput();
        tokenInput.id = tokenId;
        Response<AuthTokenInfo> resp =
            createToken.apply(rsrc, IdString.fromDecoded(tokenInput.id), tokenInput);
        stdout.print(String.format("New token (id = %s): %s", resp.value().id, resp.value().token));
      }

      for (String tokenId : tokenIdsToDelete) {
        deleteToken.apply(rsrc.getUser(), tokenId, true);
      }

      if (active) {
        @SuppressWarnings("unused")
        var unused = putActive.apply(rsrc, null);
      } else if (inactive) {
        try {
          @SuppressWarnings("unused")
          var unused = deleteActive.apply(rsrc, null);
        } catch (ResourceNotFoundException e) {
          // user is already inactive
        }
      }

      addSshKeys = readSshKey(addSshKeys);
      if (!addSshKeys.isEmpty()) {
        addSshKeys(addSshKeys);
      }

      deleteSshKeys = readSshKey(deleteSshKeys);
      if (!deleteSshKeys.isEmpty()) {
        deleteSshKeys(deleteSshKeys);
      }

      for (String externalId : externalIdsToDelete) {
        deleteExternalId(externalId);
      }
    } catch (RestApiException e) {
      throw die(e.getMessage());
    } catch (Exception e) {
      throw new Failure(1, "unavailable", e);
    }
  }

  private void addSshKeys(List<String> sshKeys)
      throws RestApiException, IOException, ConfigInvalidException, PermissionBackendException {
    for (String sshKey : sshKeys) {
      SshKeyInput in = new SshKeyInput();
      in.raw = RawInputUtil.create(sshKey.getBytes(UTF_8), "text/plain");

      @SuppressWarnings("unused")
      var unused = addSshKey.apply(rsrc, in);
    }
  }

  private void deleteSshKeys(List<String> sshKeys) throws Exception {
    List<SshKeyInfo> infos = getSshKeys.apply(rsrc).value();
    if (sshKeys.contains("ALL")) {
      for (SshKeyInfo i : infos) {
        deleteSshKey(i);
      }
    } else {
      for (String sshKey : sshKeys) {
        for (SshKeyInfo i : infos) {
          if (sshKey.trim().equals(i.sshPublicKey) || sshKey.trim().equals(i.comment)) {
            deleteSshKey(i);
          }
        }
      }
    }
  }

  private void deleteSshKey(SshKeyInfo i)
      throws AuthException,
          RepositoryNotFoundException,
          IOException,
          ConfigInvalidException,
          PermissionBackendException {
    AccountSshKey sshKey = AccountSshKey.create(user.getAccountId(), i.seq, i.sshPublicKey);

    @SuppressWarnings("unused")
    var unused =
        deleteSshKey.apply(new AccountResource.SshKey(user.asIdentifiedUser(), sshKey), null);
  }

  private void addEmail(String email)
      throws UnloggedFailure,
          RestApiException,
          IOException,
          ConfigInvalidException,
          PermissionBackendException {
    EmailInput in = new EmailInput();
    in.email = email;
    in.noConfirmation = true;
    try {
      @SuppressWarnings("unused")
      var unused = createEmail.apply(rsrc, IdString.fromDecoded(email), in);
    } catch (EmailException e) {
      throw die(e.getMessage());
    }
  }

  private void deleteEmail(String email) throws Exception {
    if (email.equals("ALL")) {
      List<EmailInfo> emails = getEmails.apply(rsrc).value();
      for (EmailInfo e : emails) {
        @SuppressWarnings("unused")
        var unused =
            deleteEmail.apply(
                new AccountResource.Email(user.asIdentifiedUser(), e.email), new Input());
      }
    } else {
      @SuppressWarnings("unused")
      var unused =
          deleteEmail.apply(new AccountResource.Email(user.asIdentifiedUser(), email), new Input());
    }
  }

  private void putPreferred(String email) throws Exception {
    for (EmailInfo e : getEmails.apply(rsrc).value()) {
      if (e.email.equals(email)) {
        @SuppressWarnings("unused")
        var unused =
            putPreferred.apply(new AccountResource.Email(user.asIdentifiedUser(), email), null);
        return;
      }
    }
    stderr.println("preferred email not found: " + email);
  }

  private List<String> readSshKey(List<String> sshKeys)
      throws UnsupportedEncodingException, IOException {
    if (!sshKeys.isEmpty()) {
      int idx = sshKeys.indexOf("-");
      if (idx >= 0) {
        StringBuilder sshKey = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
          sshKey.append(line).append("\n");
        }
        sshKeys.set(idx, sshKey.toString());
      }
    }
    return sshKeys;
  }

  private void deleteExternalId(String externalId)
      throws IOException, RestApiException, ConfigInvalidException, PermissionBackendException {
    List<String> ids;
    if (externalId.equals("ALL")) {
      ids =
          externalIds.byAccount(rsrc.getUser().getAccountId()).stream()
              .map(e -> e.key().get())
              .collect(toList());
      if (ids.isEmpty()) {
        throw new ResourceNotFoundException("Account has no external Ids");
      }
    } else {
      ids = Collections.singletonList(externalId);
    }

    @SuppressWarnings("unused")
    var unused = deleteExternalIds.apply(rsrc, ids);
  }
}
