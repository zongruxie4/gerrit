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

package com.google.gerrit.server.restapi.change;

import static com.google.gerrit.extensions.conditions.BooleanCondition.and;
import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ContributorAgreementsChecker;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.UpdateException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class CherryPick
    implements RestModifyView<RevisionResource, CherryPickInput>, UiAction<RevisionResource> {
  private final PermissionBackend permissionBackend;
  private final CherryPickChange cherryPickChange;
  private final ChangeJson.Factory json;
  private final ContributorAgreementsChecker contributorAgreements;
  private final ProjectCache projectCache;

  @Inject
  CherryPick(
      PermissionBackend permissionBackend,
      CherryPickChange cherryPickChange,
      ChangeJson.Factory json,
      ContributorAgreementsChecker contributorAgreements,
      ProjectCache projectCache) {
    this.permissionBackend = permissionBackend;
    this.cherryPickChange = cherryPickChange;
    this.json = json;
    this.contributorAgreements = contributorAgreements;
    this.projectCache = projectCache;
  }

  @Override
  public Response<ChangeInfo> apply(RevisionResource rsrc, CherryPickInput input)
      throws IOException,
          UpdateException,
          RestApiException,
          PermissionBackendException,
          ConfigInvalidException,
          NoSuchProjectException {
    input.parent = input.parent == null ? 1 : input.parent;
    if (input.destination == null || input.destination.trim().isEmpty()) {
      throw new BadRequestException("destination must be non-empty");
    }

    String refName = RefNames.fullName(input.destination);
    contributorAgreements.check(rsrc.getProject(), rsrc.getUser());

    permissionBackend
        .currentUser()
        .project(rsrc.getChange().getProject())
        .ref(refName)
        .check(RefPermission.CREATE_CHANGE);
    projectCache
        .get(rsrc.getProject())
        .orElseThrow(illegalState(rsrc.getProject()))
        .checkStatePermitsWrite();

    try {
      CherryPickChange.Result cherryPickResult =
          cherryPickChange.cherryPick(
              rsrc.getChange(),
              rsrc.getPatchSet(),
              input,
              BranchNameKey.create(rsrc.getProject(), refName));
      ChangeInfo changeInfo =
          json.noOptions().format(rsrc.getProject(), cherryPickResult.changeId());
      changeInfo.containsGitConflicts =
          !cherryPickResult.filesWithGitConflicts().isEmpty() ? true : null;
      return Response.ok(changeInfo);
    } catch (InvalidChangeOperationException e) {
      throw new BadRequestException(e.getMessage());
    } catch (NoSuchChangeException e) {
      throw new ResourceConflictException(e.getMessage());
    }
  }

  @Override
  public UiAction.Description getDescription(RevisionResource rsrc) {
    boolean projectStatePermitsWrite =
        projectCache.get(rsrc.getProject()).map(ProjectState::statePermitsWrite).orElse(false);
    return new UiAction.Description()
        .setLabel("Cherry Pick")
        .setTitle("Cherry pick change to a different branch")
        .setVisible(
            and(
                rsrc.isCurrent() && projectStatePermitsWrite,
                permissionBackend
                    .currentUser()
                    .project(rsrc.getProject())
                    .testCond(ProjectPermission.CREATE_CHANGE)));
  }
}
