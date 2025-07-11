// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.acceptance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.TestActionRefUpdateContext.openTestRefUpdateContext;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AllUsersNameProvider;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.group.GroupIndexer;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.testing.InMemoryRepositoryManager;
import com.google.gerrit.testing.TestTimeUtil;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProjectResetterTest {
  private InMemoryRepositoryManager repoManager;
  private Project.NameKey project;
  private Repository repo;

  @Before
  public void setUp() throws Exception {
    repoManager = new InMemoryRepositoryManager();
    project = Project.nameKey("foo");
    repo = repoManager.createRepository(project);
  }

  @Before
  public void setTimeForTesting() {
    TestTimeUtil.resetWithClockStep(1, TimeUnit.SECONDS);
  }

  @After
  public void resetTime() {
    TestTimeUtil.useSystemTime();
  }

  @Test
  public void resetAllRefs() throws Exception {
    Ref matchingRef = createRef("refs/any/test");

    try (ProjectResetter resetProject =
        builder().build(new ProjectResetter.Config.Builder().reset(project).build())) {
      updateRef(matchingRef);
    }

    // The matching refs are reset to the old state.
    assertRef(matchingRef);
  }

  @Test
  public void onlyResetMatchingRefs() throws Exception {
    Ref matchingRef = createRef("refs/match/test");
    Ref anotherMatchingRef = createRef("refs/another-match/test");
    Ref nonMatchingRef = createRef("refs/no-match/test");

    Ref updatedNonMatchingRef;
    try (ProjectResetter resetProject =
        builder()
            .build(
                new ProjectResetter.Config.Builder()
                    .reset(project, "refs/match/*", "refs/another-match/*")
                    .build())) {
      updateRef(matchingRef);
      updateRef(anotherMatchingRef);
      updatedNonMatchingRef = updateRef(nonMatchingRef);
    }

    // The matching refs are reset to the old state.
    assertRef(matchingRef);
    assertRef(anotherMatchingRef);

    // The non-matching ref is not reset, hence it still has the updated state.
    assertRef(updatedNonMatchingRef);
  }

  @Test
  public void onlyDeleteNewlyCreatedMatchingRefs() throws Exception {
    Ref matchingRef;
    Ref anotherMatchingRef;
    Ref nonMatchingRef;
    try (ProjectResetter resetProject =
        builder()
            .build(
                new ProjectResetter.Config.Builder()
                    .reset(project, "refs/match/*", "refs/another-match/*")
                    .build())) {
      matchingRef = createRef("refs/match/test");
      anotherMatchingRef = createRef("refs/another-match/test");
      nonMatchingRef = createRef("refs/no-match/test");
    }

    // The matching refs are deleted since they didn't exist before.
    assertDeletedRef(matchingRef);
    assertDeletedRef(anotherMatchingRef);

    // The non-matching ref is not deleted.
    assertRef(nonMatchingRef);
  }

  @Test
  public void onlyResetMatchingRefsMultipleProjects() throws Exception {
    Project.NameKey project2 = Project.nameKey("bar");
    Repository repo2 = repoManager.createRepository(project2);

    Ref matchingRefProject1 = createRef("refs/foo/test");
    Ref nonMatchingRefProject1 = createRef("refs/bar/test");

    Ref matchingRefProject2 = createRef(repo2, "refs/bar/test");
    Ref nonMatchingRefProject2 = createRef(repo2, "refs/foo/test");

    Ref updatedNonMatchingRefProject1;
    Ref updatedNonMatchingRefProject2;
    try (ProjectResetter resetProject =
        builder()
            .build(
                new ProjectResetter.Config.Builder()
                    .reset(project, "refs/foo/*")
                    .reset(project2, "refs/bar/*")
                    .build())) {
      updateRef(matchingRefProject1);
      updatedNonMatchingRefProject1 = updateRef(nonMatchingRefProject1);

      updateRef(repo2, matchingRefProject2);
      updatedNonMatchingRefProject2 = updateRef(repo2, nonMatchingRefProject2);
    }

    // The matching refs are reset to the old state.
    assertRef(matchingRefProject1);
    assertRef(repo2, matchingRefProject2);

    // The non-matching refs are not reset, hence they still has the updated states.
    assertRef(updatedNonMatchingRefProject1);
    assertRef(repo2, updatedNonMatchingRefProject2);
  }

  @Test
  public void onlyDeleteNewlyCreatedMatchingRefsMultipleProjects() throws Exception {
    Project.NameKey project2 = Project.nameKey("bar");
    Repository repo2 = repoManager.createRepository(project2);

    Ref matchingRefProject1;
    Ref nonMatchingRefProject1;
    Ref matchingRefProject2;
    Ref nonMatchingRefProject2;
    try (ProjectResetter resetProject =
        builder()
            .build(
                new ProjectResetter.Config.Builder()
                    .reset(project, "refs/foo/*")
                    .reset(project2, "refs/bar/*")
                    .build())) {
      matchingRefProject1 = createRef("refs/foo/test");
      nonMatchingRefProject1 = createRef("refs/bar/test");

      matchingRefProject2 = createRef(repo2, "refs/bar/test");
      nonMatchingRefProject2 = createRef(repo2, "refs/foo/test");
    }

    // The matching refs are deleted since they didn't exist before.
    assertDeletedRef(matchingRefProject1);
    assertDeletedRef(repo2, matchingRefProject2);

    // The non-matching ref is not deleted.
    assertRef(nonMatchingRefProject1);
    assertRef(repo2, nonMatchingRefProject2);
  }

  @Test
  public void onlyDeleteNewlyCreatedWithOverlappingRefPatterns() throws Exception {
    Ref matchingRef;
    try (ProjectResetter resetProject =
        builder()
            .build(
                new ProjectResetter.Config.Builder()
                    .reset(project, "refs/match/*", "refs/match/test")
                    .build())) {
      // This ref matches 2 ref pattern, ProjectResetter should try to delete it only once.
      matchingRef = createRef("refs/match/test");
    }

    // The matching ref is deleted since it didn't exist before.
    assertDeletedRef(matchingRef);
  }

  @Test
  public void projectEvictionIfRefsMetaConfigIsReset() throws Exception {
    Project.NameKey project2 = Project.nameKey("bar");
    Repository repo2 = repoManager.createRepository(project2);
    Ref metaConfig = createRef(repo2, RefNames.REFS_CONFIG);

    ProjectCache projectCache = mock(ProjectCache.class);

    Ref nonMetaConfig = createRef("refs/heads/master");

    try (ProjectResetter resetProject =
        builder(null, null, null, null, null, null, projectCache)
            .build(new ProjectResetter.Config.Builder().reset(project).reset(project2).build())) {
      updateRef(nonMetaConfig);
      updateRef(repo2, metaConfig);
    }

    verify(projectCache, only()).evictAndReindex(project2);
  }

  @Test
  public void projectEvictionIfRefsMetaConfigIsDeleted() throws Exception {
    Project.NameKey project2 = Project.nameKey("bar");
    Repository repo2 = repoManager.createRepository(project2);

    ProjectCache projectCache = mock(ProjectCache.class);

    try (ProjectResetter resetProject =
        builder(null, null, null, null, null, null, projectCache)
            .build(new ProjectResetter.Config.Builder().reset(project).reset(project2).build())) {
      createRef("refs/heads/master");
      createRef(repo2, RefNames.REFS_CONFIG);
    }

    verify(projectCache, only()).evictAndReindex(project2);
  }

  @Test
  public void groupEviction() throws Exception {
    AccountGroup.UUID uuid1 = AccountGroup.uuid("abcd1");
    AccountGroup.UUID uuid2 = AccountGroup.uuid("abcd2");
    AccountGroup.UUID uuid3 = AccountGroup.uuid("abcd3");
    Project.NameKey allUsers = Project.nameKey(AllUsersNameProvider.DEFAULT);
    Repository allUsersRepo = repoManager.createRepository(allUsers);

    GroupCache cache = mock(GroupCache.class);
    GroupIndexer indexer = mock(GroupIndexer.class);
    GroupIncludeCache includeCache = mock(GroupIncludeCache.class);

    createRef(allUsersRepo, RefNames.refsGroups(uuid1));
    Ref ref2 = createRef(allUsersRepo, RefNames.refsGroups(uuid2));
    try (ProjectResetter resetProject =
        builder(null, null, null, cache, includeCache, indexer, null)
            .build(new ProjectResetter.Config.Builder().reset(project).reset(allUsers).build())) {
      updateRef(allUsersRepo, ref2);
      createRef(allUsersRepo, RefNames.refsGroups(uuid3));
    }

    verify(cache).evict(uuid2);
    verify(indexer).index(uuid2);
    verify(includeCache).evictParentGroupsOf(uuid2);
    verify(cache).evict(uuid3);
    verify(indexer).index(uuid3);
    verify(includeCache).evictParentGroupsOf(uuid3);
    verifyNoMoreInteractions(cache, indexer, includeCache);
  }

  @Test
  public void newCreateProjectsAreDeleted() throws Exception {
    Project.NameKey project2 = Project.nameKey("bar");

    ProjectCache projectCache = mock(ProjectCache.class);

    try (ProjectResetter resetProject =
        builder(null, null, null, null, null, null, projectCache)
            .build(new ProjectResetter.Config.Builder().deleteNewProjects().build())) {
      Repository repo2 = repoManager.createRepository(project2);
      createRef("refs/heads/master");
      createRef(repo2, RefNames.REFS_CONFIG);
    }

    assertThat(repoManager.list()).doesNotContain(project2);
    verify(projectCache, only()).evictAndReindex(project2);
  }

  @CanIgnoreReturnValue
  private Ref createRef(String ref) throws IOException {
    return createRef(repo, ref);
  }

  @CanIgnoreReturnValue
  private Ref createRef(Repository repo, String ref) throws IOException {
    try (RefUpdateContext ctx = openTestRefUpdateContext()) {
      try (ObjectInserter oi = repo.newObjectInserter();
          RevWalk rw = new RevWalk(repo)) {
        ObjectId emptyCommit = createCommit(repo);
        RefUpdate updateRef = repo.updateRef(ref);
        updateRef.setExpectedOldObjectId(ObjectId.zeroId());
        updateRef.setNewObjectId(emptyCommit);
        assertThat(updateRef.update(rw)).isEqualTo(RefUpdate.Result.NEW);
        return repo.exactRef(ref);
      }
    }
  }

  @CanIgnoreReturnValue
  private Ref updateRef(Ref ref) throws IOException {
    return updateRef(repo, ref);
  }

  @CanIgnoreReturnValue
  private Ref updateRef(Repository repo, Ref ref) throws IOException {
    try (RefUpdateContext ctx = openTestRefUpdateContext()) {
      try (ObjectInserter oi = repo.newObjectInserter();
          RevWalk rw = new RevWalk(repo)) {
        ObjectId emptyCommit = createCommit(repo);
        RefUpdate updateRef = repo.updateRef(ref.getName());
        updateRef.setExpectedOldObjectId(ref.getObjectId());
        updateRef.setNewObjectId(emptyCommit);
        updateRef.setForceUpdate(true);
        assertThat(updateRef.update(rw)).isEqualTo(RefUpdate.Result.FORCED);
        Ref updatedRef = repo.exactRef(ref.getName());
        assertThat(updatedRef.getObjectId()).isNotEqualTo(ref.getObjectId());
        return updatedRef;
      }
    }
  }

  private void assertRef(Ref ref) throws IOException {
    assertRef(repo, ref);
  }

  private void assertRef(Repository repo, Ref ref) throws IOException {
    assertThat(repo.exactRef(ref.getName()).getObjectId()).isEqualTo(ref.getObjectId());
  }

  private void assertDeletedRef(Ref ref) throws IOException {
    assertDeletedRef(repo, ref);
  }

  private void assertDeletedRef(Repository repo, Ref ref) throws IOException {
    assertThat(repo.exactRef(ref.getName())).isNull();
  }

  private ObjectId createCommit(Repository repo) throws IOException {
    try (ObjectInserter oi = repo.newObjectInserter()) {
      PersonIdent ident =
          new PersonIdent(new PersonIdent("Foo Bar", "foo.bar@baz.com"), TimeUtil.now());
      CommitBuilder cb = new CommitBuilder();
      cb.setTreeId(oi.insert(Constants.OBJ_TREE, new byte[] {}));
      cb.setCommitter(ident);
      cb.setAuthor(ident);
      cb.setMessage("Test commit");

      ObjectId commit = oi.insert(cb);
      oi.flush();
      return commit;
    }
  }

  private ProjectResetter.Builder builder() {
    return builder(null, null, null, null, null, null, null);
  }

  private ProjectResetter.Builder builder(
      @Nullable AccountCreator accountCreator,
      @Nullable AccountCache accountCache,
      @Nullable AccountIndexer accountIndexer,
      @Nullable GroupCache groupCache,
      @Nullable GroupIncludeCache groupIncludeCache,
      @Nullable GroupIndexer groupIndexer,
      @Nullable ProjectCache projectCache) {
    return new ProjectResetter.Builder(
        repoManager,
        new AllUsersName(AllUsersNameProvider.DEFAULT),
        accountCreator,
        accountCache,
        accountIndexer,
        groupCache,
        groupIncludeCache,
        groupIndexer,
        projectCache);
  }
}
