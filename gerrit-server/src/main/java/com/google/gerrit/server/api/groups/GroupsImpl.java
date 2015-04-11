// Copyright (C) 2015 The Android Open Source Project
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

package com.google.gerrit.server.api.groups;

import com.google.gerrit.extensions.api.groups.GroupApi;
import com.google.gerrit.extensions.api.groups.Groups;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.server.account.AccountsCollection;
import com.google.gerrit.server.group.GroupsCollection;
import com.google.gerrit.server.group.ListGroups;
import com.google.gerrit.server.project.ProjectsCollection;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.SortedMap;

@Singleton
class GroupsImpl implements Groups {
  private final AccountsCollection accounts;
  private final GroupsCollection groups;
  private final ProjectsCollection projects;
  private final Provider<ListGroups> listGroups;
  private final GroupApiImpl.Factory api;

  @Inject
  GroupsImpl(
      AccountsCollection accounts,
      GroupsCollection groups,
      ProjectsCollection projects,
      Provider<ListGroups> listGroups,
      GroupApiImpl.Factory api) {
    this.accounts = accounts;
    this.groups = groups;
    this.projects = projects;
    this.listGroups = listGroups;
    this.api = api;
  }

  @Override
  public GroupApi id(String id) throws RestApiException {
    return api.create(
        groups.parse(TopLevelResource.INSTANCE, IdString.fromDecoded(id)));
  }

  @Override
  public ListRequest list() {
    return new ListRequest() {
      @Override
      public SortedMap<String, GroupInfo> getAsMap() throws RestApiException {
        return list(this);
      }
    };
  }

  private SortedMap<String, GroupInfo> list(ListRequest req)
      throws RestApiException {
    TopLevelResource tlr = TopLevelResource.INSTANCE;
    ListGroups list = listGroups.get();
    list.setOptions(req.getOptions());

    for (String project : req.getProjects()) {
      try {
        list.addProject(
            projects.parse(tlr, IdString.fromDecoded(project)).getControl());
      } catch (IOException e) {
        throw new RestApiException("Error looking up project " + project, e);
      }
    }

    for (String group : req.getGroups()) {
      list.addGroup(groups.parse(group).getGroupUUID());
    }

    list.setVisibleToAll(req.getVisibleToAll());

    if (req.getUser() != null) {
      try {
        list.setUser(accounts.parse(req.getUser()).getAccountId());
      } catch (OrmException e) {
        throw new RestApiException("Error looking up user " + req.getUser(), e);
      }
    }

    list.setOwned(req.getOwned());
    list.setLimit(req.getLimit());
    list.setStart(req.getStart());
    list.setMatchSubstring(req.getSubstring());
    try {
      return list.apply(tlr);
    } catch (OrmException e) {
      throw new RestApiException("Cannot list groups", e);
    }
  }
}
