// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.events;

import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelTypes;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeDeletedListener;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.extensions.events.CustomKeyedValuesEditedListener;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HashtagsEditedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.PrivateStateChangedListener;
import com.google.gerrit.extensions.events.ReviewerAddedListener;
import com.google.gerrit.extensions.events.ReviewerDeletedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.events.VoteDeletedListener;
import com.google.gerrit.extensions.events.WorkInProgressStateChangedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ObjectUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

@Singleton
public class StreamEventsApiListener
    implements ChangeAbandonedListener,
        ChangeDeletedListener,
        ChangeMergedListener,
        ChangeRestoredListener,
        WorkInProgressStateChangedListener,
        PrivateStateChangedListener,
        CommentAddedListener,
        GitReferenceUpdatedListener,
        GitBatchRefUpdateListener,
        HashtagsEditedListener,
        CustomKeyedValuesEditedListener,
        NewProjectCreatedListener,
        ReviewerAddedListener,
        ReviewerDeletedListener,
        RevisionCreatedListener,
        TopicEditedListener,
        VoteDeletedListener,
        HeadUpdatedListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static class StreamEventsApiListenerModule extends AbstractModule {
    private final Config config;

    public StreamEventsApiListenerModule(Config config) {
      this.config = config;
    }

    @Override
    protected void configure() {
      DynamicSet.bind(binder(), ChangeAbandonedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), ChangeDeletedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), ChangeMergedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), ChangeRestoredListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), CommentAddedListener.class).to(StreamEventsApiListener.class);
      if (config.getBoolean("event", "stream-events", "enableRefUpdatedEvents", true)) {
        DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
            .to(StreamEventsApiListener.class);
      }
      if (config.getBoolean("event", "stream-events", "enableBatchRefUpdatedEvents", false)) {
        DynamicSet.bind(binder(), GitBatchRefUpdateListener.class)
            .to(StreamEventsApiListener.class);
      }
      DynamicSet.bind(binder(), HashtagsEditedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), CustomKeyedValuesEditedListener.class)
          .to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), NewProjectCreatedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), PrivateStateChangedListener.class)
          .to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), ReviewerAddedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), ReviewerDeletedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), RevisionCreatedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), TopicEditedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), VoteDeletedListener.class).to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), WorkInProgressStateChangedListener.class)
          .to(StreamEventsApiListener.class);
      DynamicSet.bind(binder(), HeadUpdatedListener.class).to(StreamEventsApiListener.class);
    }
  }

  private final PluginItemContext<EventDispatcher> dispatcher;
  private final EventFactory eventFactory;
  private final ProjectCache projectCache;
  private final GitRepositoryManager repoManager;
  private final PatchSetUtil psUtil;
  private final ChangeNotes.Factory changeNotesFactory;
  private final boolean enableDraftCommentEvents;
  private final ChangeData.Factory changeDataFactory;

  private final String gerritInstanceId;

  @Inject
  StreamEventsApiListener(
      PluginItemContext<EventDispatcher> dispatcher,
      EventFactory eventFactory,
      ProjectCache projectCache,
      GitRepositoryManager repoManager,
      PatchSetUtil psUtil,
      ChangeNotes.Factory changeNotesFactory,
      @GerritServerConfig Config config,
      @Nullable @GerritInstanceId String gerritInstanceId,
      ChangeData.Factory changeDataFactory) {
    this.dispatcher = dispatcher;
    this.eventFactory = eventFactory;
    this.projectCache = projectCache;
    this.repoManager = repoManager;
    this.psUtil = psUtil;
    this.changeNotesFactory = changeNotesFactory;
    this.enableDraftCommentEvents =
        config.getBoolean("event", "stream-events", "enableDraftCommentEvents", false);
    this.gerritInstanceId = gerritInstanceId;
    this.changeDataFactory = changeDataFactory;
  }

  private ChangeNotes getNotes(ChangeInfo info) {
    try {
      return changeNotesFactory.createChecked(
          Project.nameKey(info.project), Change.id(info._number));
    } catch (NoSuchChangeException e) {
      throw new StorageException(e);
    }
  }

  private PatchSet getPatchSet(ChangeNotes notes, RevisionInfo info) {
    return psUtil.get(notes, PatchSet.Id.fromRef(info.ref));
  }

  private Supplier<ChangeAttribute> changeAttributeSupplier(Change change, ChangeNotes notes) {
    return Suppliers.memoize(
        () -> {
          try {
            return eventFactory.asChangeAttribute(change, notes);
          } catch (StorageException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private Supplier<AccountAttribute> accountAttributeSupplier(AccountInfo account) {
    return Suppliers.memoize(
        () ->
            account != null
                ? eventFactory.asAccountAttribute(Account.id(account._accountId))
                : null);
  }

  private Supplier<PatchSetAttribute> patchSetAttributeSupplier(
      final ChangeData changeData, PatchSet patchSet) {
    return Suppliers.memoize(
        () -> {
          try (Repository repo = repoManager.openRepository(changeData.change().getProject());
              RevWalk revWalk = new RevWalk(repo)) {
            return eventFactory.asPatchSetAttribute(
                revWalk,
                repo.getConfig(),
                repo.createAttributesNodeProvider(),
                changeData,
                patchSet);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static Map<String, Short> convertApprovalsMap(Map<String, ApprovalInfo> approvals) {
    Map<String, Short> result = new HashMap<>();
    for (Map.Entry<String, ApprovalInfo> e : approvals.entrySet()) {
      Short value = e.getValue().value == null ? null : e.getValue().value.shortValue();
      result.put(e.getKey(), value);
    }
    return result;
  }

  private ApprovalAttribute getApprovalAttribute(
      LabelTypes labelTypes, Map.Entry<String, Short> approval, Map<String, Short> oldApprovals) {
    ApprovalAttribute a = new ApprovalAttribute();
    a.type = approval.getKey();

    if (oldApprovals != null && !oldApprovals.isEmpty()) {
      if (oldApprovals.get(approval.getKey()) != null) {
        a.oldValue = Short.toString(oldApprovals.get(approval.getKey()));
      }
    }
    labelTypes.byLabel(approval.getKey()).ifPresent(lt -> a.description = lt.getName());
    if (approval.getValue() != null) {
      a.value = Short.toString(approval.getValue());
    }
    return a;
  }

  private Supplier<ApprovalAttribute[]> approvalsAttributeSupplier(
      final Change change,
      Map<String, ApprovalInfo> newApprovals,
      final Map<String, ApprovalInfo> oldApprovals) {
    final Map<String, Short> approvals = convertApprovalsMap(newApprovals);
    return Suppliers.memoize(
        () -> {
          Project.NameKey nameKey = change.getProject();
          LabelTypes labelTypes =
              projectCache.get(nameKey).orElseThrow(illegalState(nameKey)).getLabelTypes();
          if (approvals.size() > 0) {
            ApprovalAttribute[] r = new ApprovalAttribute[approvals.size()];
            int i = 0;
            for (Map.Entry<String, Short> approval : approvals.entrySet()) {
              r[i++] =
                  getApprovalAttribute(labelTypes, approval, convertApprovalsMap(oldApprovals));
            }
            return r;
          }
          return null;
        });
  }

  @Nullable
  String[] hashArray(Collection<String> collection) {
    if (collection != null && !collection.isEmpty()) {
      return Sets.newHashSet(collection).toArray(new String[collection.size()]);
    }
    return null;
  }

  @Override
  public void onTopicEdited(TopicEditedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      TopicChangedEvent event = new TopicChangedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.changer = accountAttributeSupplier(ev.getWho());
      event.oldTopic = ev.getOldTopic();

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      PatchSet patchSet = getPatchSet(notes, ev.getRevision());
      PatchSetCreatedEvent event = new PatchSetCreatedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.patchSet = patchSetAttributeSupplier(changeDataFactory.create(notes), patchSet);
      event.uploader = accountAttributeSupplier(ev.getWho());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onReviewerDeleted(ReviewerDeletedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ReviewerDeletedEvent event = new ReviewerDeletedEvent(change);
      event.change = changeAttributeSupplier(change, notes);
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.reviewer = accountAttributeSupplier(ev.getReviewer());
      event.remover = accountAttributeSupplier(ev.getWho());
      event.comment = ev.getComment();
      event.approvals =
          approvalsAttributeSupplier(change, ev.getNewApprovals(), ev.getOldApprovals());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onReviewersAdded(ReviewerAddedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ReviewerAddedEvent event = new ReviewerAddedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.adder = accountAttributeSupplier(ev.getWho());
      for (AccountInfo reviewer : ev.getReviewers()) {
        event.reviewer = accountAttributeSupplier(reviewer);
        dispatcher.run(d -> d.postEvent(event));
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onNewProjectCreated(NewProjectCreatedListener.Event ev) {
    if (!Objects.equals(ev.getInstanceId(), gerritInstanceId)) {
      logger.atFine().log(
          "Ignoring project-created event for project %s (instanceId: %s)",
          ev.getProjectName(), ObjectUtils.firstNonNull(ev.getInstanceId(), "Not defined"));
      return;
    }
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.projectName = ev.getProjectName();
    event.headName = ev.getHeadName();

    dispatcher.run(d -> d.postEvent(event.getProjectNameKey(), event));
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event ev) {
    ProjectHeadUpdatedEvent event = new ProjectHeadUpdatedEvent();
    event.projectName = ev.getProjectName();
    event.oldHead = ev.getOldHeadName();
    event.newHead = ev.getNewHeadName();

    dispatcher.run(d -> d.postEvent(event.getProjectNameKey(), event));
  }

  @Override
  public void onHashtagsEdited(HashtagsEditedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      HashtagsChangedEvent event = new HashtagsChangedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.editor = accountAttributeSupplier(ev.getWho());
      event.hashtags = hashArray(ev.getHashtags());
      event.added = hashArray(ev.getAddedHashtags());
      event.removed = hashArray(ev.getRemovedHashtags());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onCustomKeyedValuesEdited(CustomKeyedValuesEditedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      CustomKeyedValuesChangedEvent event = new CustomKeyedValuesChangedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.editor = accountAttributeSupplier(ev.getWho());
      event.customKeyedValues = ev.getCustomKeyedValues();
      event.added = ev.getAddedCustomKeyedValues();
      event.removed = hashArray(ev.getRemovedCustomKeys());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event ev) {
    RefUpdatedEvent event = new RefUpdatedEvent();
    if (ev.getUpdater() != null) {
      event.submitter = accountAttributeSupplier(ev.getUpdater());
    }
    final BranchNameKey refName = BranchNameKey.create(ev.getProjectName(), ev.getRefName());
    event.refUpdate =
        Suppliers.memoize(
            () ->
                eventFactory.asRefUpdateAttribute(
                    ObjectId.fromString(ev.getOldObjectId()),
                    ObjectId.fromString(ev.getNewObjectId()),
                    refName));

    if (enableDraftCommentEvents || !RefNames.isRefsDraftsComments(event.getRefName())) {
      dispatcher.run(d -> d.postEvent(refName, event));
    }
  }

  @Override
  public void onGitBatchRefUpdate(GitBatchRefUpdateListener.Event ev) {
    Project.NameKey projectName = Project.nameKey(ev.getProjectName());
    Supplier<List<RefUpdateAttribute>> refUpdates =
        Suppliers.memoize(
            () ->
                ev.getUpdatedRefs().stream()
                    .filter(
                        refUpdate ->
                            enableDraftCommentEvents
                                || !RefNames.isRefsDraftsComments(refUpdate.getRefName()))
                    .map(
                        ru ->
                            eventFactory.asRefUpdateAttribute(
                                ObjectId.fromString(ru.getOldObjectId()),
                                ObjectId.fromString(ru.getNewObjectId()),
                                BranchNameKey.create(ev.getProjectName(), ru.getRefName())))
                    .collect(Collectors.toList()));

    Supplier<AccountAttribute> submitterSupplier = accountAttributeSupplier(ev.getUpdater());
    BatchRefUpdateEvent event = new BatchRefUpdateEvent(projectName, refUpdates, submitterSupplier);
    dispatcher.run(d -> d.postEvent(projectName, event));
  }

  @Override
  public void onCommentAdded(CommentAddedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      PatchSet ps = getPatchSet(notes, ev.getRevision());
      CommentAddedEvent event = new CommentAddedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.author = accountAttributeSupplier(ev.getWho());
      event.patchSet = patchSetAttributeSupplier(changeDataFactory.create(notes), ps);
      event.comment = ev.getComment();
      event.approvals = approvalsAttributeSupplier(change, ev.getApprovals(), ev.getOldApprovals());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onChangeRestored(ChangeRestoredListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ChangeRestoredEvent event = new ChangeRestoredEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.restorer = accountAttributeSupplier(ev.getWho());
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.reason = ev.getReason();

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onChangeMerged(ChangeMergedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ChangeMergedEvent event = new ChangeMergedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.submitter = accountAttributeSupplier(ev.getWho());
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.newRev = ev.getNewRevisionId();

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onChangeAbandoned(ChangeAbandonedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ChangeAbandonedEvent event = new ChangeAbandonedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.abandoner = accountAttributeSupplier(ev.getWho());
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.reason = ev.getReason();

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onWorkInProgressStateChanged(WorkInProgressStateChangedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      PatchSet patchSet = getPatchSet(notes, ev.getRevision());
      WorkInProgressStateChangedEvent event = new WorkInProgressStateChangedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.changer = accountAttributeSupplier(ev.getWho());
      event.patchSet = patchSetAttributeSupplier(changeDataFactory.create(notes), patchSet);

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onPrivateStateChanged(PrivateStateChangedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      PatchSet patchSet = getPatchSet(notes, ev.getRevision());
      PrivateStateChangedEvent event = new PrivateStateChangedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.changer = accountAttributeSupplier(ev.getWho());
      event.patchSet = patchSetAttributeSupplier(changeDataFactory.create(notes), patchSet);

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onVoteDeleted(VoteDeletedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      VoteDeletedEvent event = new VoteDeletedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.patchSet =
          patchSetAttributeSupplier(changeDataFactory.create(notes), psUtil.current(notes));
      event.comment = ev.getMessage();
      event.reviewer = accountAttributeSupplier(ev.getReviewer());
      event.remover = accountAttributeSupplier(ev.getWho());
      event.approvals = approvalsAttributeSupplier(change, ev.getApprovals(), ev.getOldApprovals());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }

  @Override
  public void onChangeDeleted(ChangeDeletedListener.Event ev) {
    try {
      ChangeNotes notes = getNotes(ev.getChange());
      Change change = notes.getChange();
      ChangeDeletedEvent event = new ChangeDeletedEvent(change);

      event.change = changeAttributeSupplier(change, notes);
      event.deleter = accountAttributeSupplier(ev.getWho());

      dispatcher.run(d -> d.postEvent(change, event));
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Failed to dispatch event");
    }
  }
}
