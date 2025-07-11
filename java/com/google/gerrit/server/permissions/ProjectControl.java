// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.permissions;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.gerrit.entities.AccessSection.ALL;
import static com.google.gerrit.entities.AccessSection.REGEX_PREFIX;
import static com.google.gerrit.entities.RefNames.REFS_TAGS;
import static com.google.gerrit.server.util.MagicBranch.NEW_CHANGE;

import com.google.common.collect.Sets;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.PermissionRule;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.access.CoreOrPluginProjectPermission;
import com.google.gerrit.extensions.api.access.PluginProjectPermission;
import com.google.gerrit.extensions.conditions.BooleanCondition;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.GitReceivePackGroups;
import com.google.gerrit.server.config.GitUploadPackGroups;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackend.ForChange;
import com.google.gerrit.server.permissions.PermissionBackend.ForProject;
import com.google.gerrit.server.permissions.PermissionBackend.ForRef;
import com.google.gerrit.server.permissions.PermissionBackend.RefFilterOptions;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SectionMatcher;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

/** Access control management for a user accessing a project's data. */
public class ProjectControl {
  public interface Factory {
    ProjectControl create(CurrentUser who, ProjectState ps);
  }

  private final Set<AccountGroup.UUID> uploadGroups;
  private final Set<AccountGroup.UUID> receiveGroups;
  private final PermissionBackend permissionBackend;
  private final CurrentUser user;
  private final ProjectState state;
  private final PermissionCollection.Factory permissionFilter;
  private final DefaultRefFilter.Factory refFilterFactory;
  private final AllUsersName allUsersName;
  private final RefControl.Factory refControlFactory;
  private final ChangeControl.Factory changeControlFactory;

  private List<SectionMatcher> allSections;
  private Map<String, RefControl> refControls;
  private Boolean declaredOwner;
  private Config cfg;

  @Inject
  protected ProjectControl(
      @GitUploadPackGroups Set<AccountGroup.UUID> uploadGroups,
      @GitReceivePackGroups Set<AccountGroup.UUID> receiveGroups,
      PermissionCollection.Factory permissionFilter,
      PermissionBackend permissionBackend,
      DefaultRefFilter.Factory refFilterFactory,
      AllUsersName allUsersName,
      @GerritServerConfig Config cfg,
      RefControl.Factory refControlFactory,
      ChangeControl.Factory changeControlFactory,
      @Assisted CurrentUser who,
      @Assisted ProjectState ps) {
    this.uploadGroups = uploadGroups;
    this.receiveGroups = receiveGroups;
    this.permissionFilter = permissionFilter;
    this.permissionBackend = permissionBackend;
    this.refFilterFactory = refFilterFactory;
    this.allUsersName = allUsersName;
    this.cfg = cfg;
    this.refControlFactory = refControlFactory;
    this.changeControlFactory = changeControlFactory;
    user = who;
    state = ps;
  }

  ForProject asForProject() {
    return new ForProjectImpl();
  }

  ChangeControl controlFor(ChangeData cd) {
    return changeControlFactory.create(this, controlForRef(cd.branchOrThrow()), cd);
  }

  ChangeControlForChangeToBeCreated controlForChangeToBeCreated(
      RefControl refControl, boolean isOwner) {
    return new ChangeControlForChangeToBeCreated(this, refControl, isOwner);
  }

  RefControl controlForRef(BranchNameKey ref) {
    return controlForRef(ref.branch());
  }

  public RefControl controlForRef(String refName) {
    if (refControls == null) {
      refControls = new HashMap<>();
    }
    RefControl ctl = refControls.get(refName);
    if (ctl == null) {
      PermissionCollection relevant = permissionFilter.filter(access(), refName, user);
      ctl = refControlFactory.create(this, refName, relevant);
      refControls.put(refName, ctl);
    }
    return ctl;
  }

  protected CurrentUser getUser() {
    return user;
  }

  protected ProjectState getProjectState() {
    return state;
  }

  Project getProject() {
    return state.getProject();
  }

  /** Is this user a project owner? */
  boolean isOwner() {
    return (isDeclaredOwner() && controlForRef(ALL).canPerform(Permission.OWNER)) || isAdmin();
  }

  /**
   * Returns {@code Capable.OK} if the user can upload to at least one reference. Does not check
   * Contributor Agreements.
   */
  boolean canPushToAtLeastOneRef() {
    return canPerformOnAnyRef(Permission.PUSH)
        || canPerformOnAnyRef(Permission.CREATE_TAG)
        || isOwner();
  }

  boolean isAdmin() {
    try {
      return permissionBackend.user(user).test(GlobalPermission.ADMINISTRATE_SERVER);
    } catch (PermissionBackendException e) {
      return false;
    }
  }

  boolean match(PermissionRule rule, boolean isChangeOwner) {
    return match(rule.getGroup().getUUID(), isChangeOwner);
  }

  boolean allRefsAreVisible(Set<String> ignore) {
    return user.isInternalUser()
        || (!getProject().getNameKey().equals(allUsersName)
            && canPerformOnAllRefs(Permission.READ, ignore));
  }

  /** Can the user run upload pack? */
  private boolean canRunUploadPack() {
    for (AccountGroup.UUID group : uploadGroups) {
      if (match(group)) {
        return true;
      }
    }
    return false;
  }

  /** Can the user run receive pack? */
  private boolean canRunReceivePack() {
    for (AccountGroup.UUID group : receiveGroups) {
      if (match(group)) {
        return true;
      }
    }
    return false;
  }

  private boolean canAddRefs() {
    return (canPerformOnAnyRef(Permission.CREATE) || isAdmin());
  }

  private boolean canAddTagRefs() {
    return (canPerformOnTagRef(Permission.CREATE) || isAdmin());
  }

  private boolean canCreateChanges() {
    for (SectionMatcher matcher : access()) {
      AccessSection section = matcher.getSection();
      if (section.getName().startsWith(NEW_CHANGE)
          || section.getName().startsWith(REGEX_PREFIX + NEW_CHANGE)) {
        Permission permission = section.getPermission(Permission.PUSH);
        if (permission != null && controlForRef(section.getName()).canPerform(Permission.PUSH)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isDeclaredOwner() {
    if (declaredOwner == null) {
      GroupMembership effectiveGroups = user.getEffectiveGroups();
      declaredOwner = effectiveGroups.containsAnyOf(state.getAllOwners());
    }
    return declaredOwner;
  }

  private boolean canPerformOnTagRef(String permissionName) {
    for (SectionMatcher matcher : access()) {
      AccessSection section = matcher.getSection();

      if (section.getName().startsWith(REFS_TAGS)
          || section.getName().startsWith(REGEX_PREFIX + REFS_TAGS)) {
        Permission permission = section.getPermission(permissionName);
        if (permission == null) {
          continue;
        }

        Boolean can = canPerform(permissionName, section, permission);
        if (can != null) {
          return can;
        }
      }
    }

    return false;
  }

  private boolean canPerformOnAnyRef(String permissionName) {
    for (SectionMatcher matcher : access()) {
      AccessSection section = matcher.getSection();
      Permission permission = section.getPermission(permissionName);
      if (permission == null) {
        continue;
      }

      Boolean can = canPerform(permissionName, section, permission);
      if (can != null) {
        return can;
      }
    }

    return false;
  }

  @Nullable
  private Boolean canPerform(String permissionName, AccessSection section, Permission permission) {
    for (PermissionRule rule : permission.getRules()) {
      if (rule.isBlock() || rule.isDeny() || !match(rule)) {
        continue;
      }

      // Being in a group that was granted this permission is only an
      // approximation.  There might be overrides and doNotInherit
      // that would render this to be false.
      //
      if (controlForRef(section.getName()).canPerform(permissionName)) {
        return true;
      }
      break;
    }
    return null;
  }

  private boolean canPerformOnAllRefs(String permission, Set<String> ignore) {
    try (TraceTimer timer =
        TraceContext.newTimer(
            "ProjectControl#canPerformOnAllRefs",
            Metadata.builder().projectName(getProject().getName()).build())) {
      boolean canPerform = false;
      Set<String> patterns = allRefPatterns(permission);
      if (patterns.contains(ALL)) {
        // Only possible if granted on the pattern that
        // matches every possible reference.  Check all
        // patterns also have the permission.
        //
        for (String pattern : patterns) {
          if (controlForRef(pattern).canPerform(permission)) {
            canPerform = true;
          } else if (ignore.contains(pattern)) {
            continue;
          } else {
            return false;
          }
        }
      }
      return canPerform;
    }
  }

  private Set<String> allRefPatterns(String permissionName) {
    Set<String> all = new HashSet<>();
    for (SectionMatcher matcher : access()) {
      AccessSection section = matcher.getSection();
      Permission permission = section.getPermission(permissionName);
      if (permission != null) {
        all.add(section.getName());
      }
    }
    return all;
  }

  private List<SectionMatcher> access() {
    if (allSections == null) {
      allSections = state.getAllSections();
    }
    return allSections;
  }

  private boolean match(PermissionRule rule) {
    return match(rule.getGroup().getUUID());
  }

  private boolean match(AccountGroup.UUID uuid) {
    return match(uuid, false);
  }

  private boolean match(AccountGroup.UUID uuid, boolean isChangeOwner) {
    if (SystemGroupBackend.PROJECT_OWNERS.equals(uuid)) {
      return isDeclaredOwner();
    } else if (SystemGroupBackend.CHANGE_OWNER.equals(uuid)) {
      return isChangeOwner;
    } else {
      return user.getEffectiveGroups().contains(uuid);
    }
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected boolean canUpdateConfigWithoutCreatingChange() {
    // In google, the implementation use more complicated logic - this is why it is placed inside
    // a ProjectControl.
    return !cfg.getBoolean("gerrit", "requireChangeForConfigUpdate", false);
  }

  private class ForProjectImpl extends ForProject {
    private String resourcePath;

    @Override
    public String resourcePath() {
      if (resourcePath == null) {
        resourcePath = "/projects/" + getProjectState().getName();
      }
      return resourcePath;
    }

    @Override
    public ForRef ref(String ref) {
      return controlForRef(ref).asForRef();
    }

    @Override
    public ForChange change(ChangeData cd) {
      try {
        checkProject(cd);
        return super.change(cd);
      } catch (StorageException e) {
        return FailedPermissionBackend.change("unavailable", e);
      }
    }

    @Override
    public ForChange change(ChangeNotes notes) {
      checkProject(notes.getChange());
      return super.change(notes);
    }

    private void checkProject(ChangeData cd) {
      checkProject(cd.project());
    }

    private void checkProject(Change change) {
      checkProject(change.getProject());
    }

    private void checkProject(Project.NameKey changeProject) {
      Project.NameKey project = getProject().getNameKey();
      checkArgument(
          project.equals(changeProject),
          "expected change in project %s, not %s",
          project,
          changeProject);
    }

    @Override
    public void check(CoreOrPluginProjectPermission perm)
        throws AuthException, PermissionBackendException {
      if (!can(perm)) {
        throw new AuthException(perm.describeForException() + " not permitted");
      }
    }

    @Override
    public <T extends CoreOrPluginProjectPermission> Set<T> test(Collection<T> permSet)
        throws PermissionBackendException {
      Set<T> ok = Sets.newHashSetWithExpectedSize(permSet.size());
      for (T perm : permSet) {
        if (can(perm)) {
          ok.add(perm);
        }
      }
      return ok;
    }

    @Override
    public BooleanCondition testCond(CoreOrPluginProjectPermission perm) {
      return new PermissionBackendCondition.ForProject(this, perm, getUser());
    }

    @Override
    public Collection<Ref> filter(Collection<Ref> refs, Repository repo, RefFilterOptions opts)
        throws PermissionBackendException {
      return refFilterFactory.create(ProjectControl.this).filter(refs, repo, opts);
    }

    private boolean can(CoreOrPluginProjectPermission perm) throws PermissionBackendException {
      if (perm instanceof ProjectPermission) {
        return can((ProjectPermission) perm);
      } else if (perm instanceof PluginProjectPermission) {
        // TODO(xchangcheng): implement for plugin defined project permissions.
        return false;
      }

      throw new PermissionBackendException(perm.describeForException() + " unsupported");
    }

    private boolean can(ProjectPermission perm) {
      return switch (perm) {
        case ACCESS -> user.isInternalUser() || isOwner() || canPerformOnAnyRef(Permission.READ);
        case READ -> allRefsAreVisible(Collections.emptySet());
        case CREATE_REF -> canAddRefs();
        case CREATE_TAG_REF -> canAddTagRefs();
        case CREATE_CHANGE -> canCreateChanges();
        case RUN_RECEIVE_PACK -> canRunReceivePack();
        case RUN_UPLOAD_PACK -> canRunUploadPack();
        case PUSH_AT_LEAST_ONE_REF -> canPushToAtLeastOneRef();
        case READ_CONFIG ->
            isOwner() || controlForRef(RefNames.REFS_CONFIG).hasReadPermissionOnRef(false);
        case BAN_COMMIT, READ_REFLOG, WRITE_CONFIG -> isOwner();
        case UPDATE_CONFIG_WITHOUT_CREATING_CHANGE -> canUpdateConfigWithoutCreatingChange();
      };
    }
  }
}
