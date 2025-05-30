// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.acceptance.rest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.truth.Expect;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestExtensions.TestRetryListener;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.SubmitRecord;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.httpd.restapi.ParameterParser;
import com.google.gerrit.httpd.restapi.RestApiServlet;
import com.google.gerrit.server.ExceptionHook;
import com.google.gerrit.server.change.ReviewerSuggestion;
import com.google.gerrit.server.change.SuggestedReviewer;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.logging.LoggingContext;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.PerformanceLogger;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.project.CreateProjectArgs;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.rules.SubmitRule;
import com.google.gerrit.server.update.RetryableAction.ActionType;
import com.google.gerrit.server.validators.ProjectCreationValidationListener;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test tests the tracing of requests.
 *
 * <p>To verify that tracing is working we do:
 *
 * <ul>
 *   <li>Register a plugin extension that we know is invoked when the request is done. Within the
 *       implementation of this plugin extension we access the status of the thread local state in
 *       the {@link LoggingContext} and store it locally in the plugin extension class.
 *   <li>Do a request (e.g. REST) that triggers the plugin extension.
 *   <li>When the plugin extension is invoked it records the current logging context.
 *   <li>After the request is done the test verifies that logging context that was recorded by the
 *       plugin extension has the expected state.
 * </ul>
 */
public class TraceIT extends AbstractDaemonTest {
  @Rule public final Expect expect = Expect.create();

  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private WorkQueue workQueue;
  @Inject private ProjectOperations projectOperations;

  @Test
  public void restCallWithoutTrace() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new1");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).hasSize(1);
      assertThat(projectCreationListener.traceIds).hasSize(1);
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new1");
    }
  }

  @Test
  public void restCallForChangeSetsProjectTag() throws Exception {
    String changeId = createChange().getChangeId();

    TraceChangeIndexedListener changeIndexedListener = new TraceChangeIndexedListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(changeIndexedListener)) {
      RestResponse response =
          adminRestSession.post(
              "/changes/" + changeId + "/revisions/current/review", ReviewInput.approve());
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);

      // The logging tag with the project name is also set if tracing is off.
      assertThat(changeIndexedListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void restCallWithTraceRequestParam() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response =
          adminRestSession.put("/projects/new2?" + ParameterParser.TRACE_PARAMETER);
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).isNotEmpty();
      assertThat(projectCreationListener.traceIds).isNotEmpty();
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new2");
    }
  }

  @Test
  public void restCallWithTraceRequestParamAndProvidedTraceId() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response =
          adminRestSession.put("/projects/new3?" + ParameterParser.TRACE_PARAMETER + "=issue/123");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).contains("issue/123");
      assertThat(projectCreationListener.traceIds).contains("issue/123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new3");
    }
  }

  @Test
  public void restCallWithTraceHeader() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response =
          adminRestSession.putWithHeaders(
              "/projects/new4", new BasicHeader(RestApiServlet.X_GERRIT_TRACE, null));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).isNotEmpty();
      assertThat(projectCreationListener.traceIds).isNotEmpty();
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new4");
    }
  }

  @Test
  public void restCallWithTraceHeaderAndProvidedTraceId() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response =
          adminRestSession.putWithHeaders(
              "/projects/new5", new BasicHeader(RestApiServlet.X_GERRIT_TRACE, "issue/123"));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).contains("issue/123");
      assertThat(projectCreationListener.traceIds).contains("issue/123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new5");
    }
  }

  @Test
  public void restCallWithTraceRequestParamAndTraceHeader() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      // trace ID only specified by trace header
      RestResponse response =
          adminRestSession.putWithHeaders(
              "/projects/new6?trace", new BasicHeader(RestApiServlet.X_GERRIT_TRACE, "issue/123"));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).contains("issue/123");
      assertThat(projectCreationListener.traceIds).contains("issue/123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new6");

      // trace ID only specified by trace request parameter
      response =
          adminRestSession.putWithHeaders(
              "/projects/new7?trace=issue/123",
              new BasicHeader(RestApiServlet.X_GERRIT_TRACE, null));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).contains("issue/123");
      assertThat(projectCreationListener.traceIds).contains("issue/123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new7");

      // same trace ID specified by trace header and trace request parameter
      response =
          adminRestSession.putWithHeaders(
              "/projects/new8?trace=issue/123",
              new BasicHeader(RestApiServlet.X_GERRIT_TRACE, "issue/123"));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).contains("issue/123");
      assertThat(projectCreationListener.traceIds).contains("issue/123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new8");

      // different trace IDs specified by trace header and trace request parameter
      response =
          adminRestSession.putWithHeaders(
              "/projects/new9?trace=issue/123",
              new BasicHeader(RestApiServlet.X_GERRIT_TRACE, "issue/456"));
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE))
          .containsAtLeast("issue/123", "issue/456");
      assertThat(projectCreationListener.traceIds).containsAtLeast("issue/123", "issue/456");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new9");
    }
  }

  @Test
  public void pushWithoutTrace() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      PushOneCommit.Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).isNotEmpty();
      assertThat(commitValidationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void pushWithTrace() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      push.setPushOptions(ImmutableList.of("trace"));
      PushOneCommit.Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).isNotEmpty();
      assertThat(commitValidationListener.isLoggingForced).isTrue();
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void pushWithTraceAndProvidedTraceId() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      push.setPushOptions(ImmutableList.of("trace=issue/123"));
      PushOneCommit.Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).contains("issue/123");
      assertThat(commitValidationListener.isLoggingForced).isTrue();
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void pushForReviewWithoutTrace() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      PushOneCommit.Result r = push.to("refs/for/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).isNotEmpty();
      assertThat(commitValidationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void pushForReviewWithTrace() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      push.setPushOptions(ImmutableList.of("trace"));
      PushOneCommit.Result r = push.to("refs/for/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).isNotEmpty();
      assertThat(commitValidationListener.isLoggingForced).isTrue();
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void pushForReviewWithTraceAndProvidedTraceId() throws Exception {
    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      push.setPushOptions(ImmutableList.of("trace=issue/123"));
      PushOneCommit.Result r = push.to("refs/for/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).contains("issue/123");
      assertThat(commitValidationListener.isLoggingForced).isTrue();
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  public void workQueueCopyLoggingContext() throws Exception {
    assertThat(LoggingContext.getInstance().getTags().isEmpty()).isTrue();
    assertForceLogging(false);
    try (TraceContext traceContext = TraceContext.open().forceLogging().addTag("foo", "bar")) {
      Map<String, ? extends Set<Object>> tagMap = LoggingContext.getInstance().getTags().asMap();
      assertThat(tagMap.keySet()).containsExactly("foo");
      assertThat(tagMap.get("foo")).containsExactly("bar");
      assertForceLogging(true);

      workQueue
          .createQueue(1, "test-queue")
          .submit(
              () -> {
                // Verify that the tags and force logging flag have been propagated to the new
                // thread.
                Map<String, ? extends Set<Object>> threadTagMap =
                    LoggingContext.getInstance().getTags().asMap();
                expect.that(threadTagMap.keySet()).containsExactly("foo");
                expect.that(threadTagMap.get("foo")).containsExactly("bar");
                expect
                    .that(LoggingContext.getInstance().shouldForceLogging(null, null, false))
                    .isTrue();
              })
          .get();

      // Verify that tags and force logging flag in the outer thread are still set.
      tagMap = LoggingContext.getInstance().getTags().asMap();
      assertThat(tagMap.keySet()).containsExactly("foo");
      assertThat(tagMap.get("foo")).containsExactly("bar");
      assertForceLogging(true);
    }
    assertThat(LoggingContext.getInstance().getTags().isEmpty()).isTrue();
    assertForceLogging(false);
  }

  @Test
  @GerritConfig(name = "tracing.performanceLogging", value = "true")
  public void performanceLoggingForRestCall() throws Exception {
    PerformanceLogger testPerformanceLogger = mock(PerformanceLogger.class);
    try (Registration registration =
        extensionRegistry.newRegistration().add(testPerformanceLogger)) {
      RestResponse response = adminRestSession.put("/projects/new10");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      verify(testPerformanceLogger, timeout(5000).atLeastOnce())
          .logNanos(anyString(), anyLong(), any());
    }
  }

  @Test
  @GerritConfig(name = "tracing.performanceLogging", value = "true")
  public void performanceLoggingForPush() throws Exception {
    PerformanceLogger testPerformanceLogger = mock(PerformanceLogger.class);
    try (Registration registration =
        extensionRegistry.newRegistration().add(testPerformanceLogger)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      PushOneCommit.Result r = push.to("refs/heads/master");
      r.assertOkStatus();
      verify(testPerformanceLogger, timeout(5000).atLeastOnce())
          .logNanos(anyString(), anyLong(), any());
    }
  }

  @Test
  public void noPerformanceLoggingByDefault() throws Exception {
    PerformanceLogger testPerformanceLogger = mock(PerformanceLogger.class);
    try (Registration registration =
        extensionRegistry.newRegistration().add(testPerformanceLogger)) {
      RestResponse response = adminRestSession.put("/projects/new11");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);

      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      PushOneCommit.Result r = push.to("refs/heads/master");
      r.assertOkStatus();

      verifyNoInteractions(testPerformanceLogger);
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new12")
  public void traceProject() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new12");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      // configuration based tracing doesn't send traceId(s) back to the client ...
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      // ... but those traceId(s) are set for logging
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new12");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new.*")
  public void traceProjectMatchRegEx() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new13");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new13");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "foo.*")
  public void traceProjectNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new13");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new13");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "][")
  public void traceProjectInvalidRegEx() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new14");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new14");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "1000000")
  public void traceAccount() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new15");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new15");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "1000001")
  public void traceAccountNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new16");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new16");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "999")
  public void traceAccountNotFound() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new17");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new17");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "invalid")
  public void traceAccountInvalidId() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new18");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new18");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestType", value = "REST")
  public void traceConfigWithRequestTypeOnlyDoesntTriggerTracing() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new19");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issues123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new19");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new20")
  @GerritConfig(name = "tracing.issue123.requestType", value = "REST")
  public void traceRequestType() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new20");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new20");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new21")
  @GerritConfig(name = "tracing.issue123.requestType", value = "SSH")
  public void traceRequestTypeNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new21");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new21");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestType", value = "GIT_RECEIVE")
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "traced-project")
  public void traceGitReceiveForProject() throws Exception {
    Project.NameKey tracedProject = projectOperations.newProject().name("traced-project").create();
    TestRepository<?> tracedRepo = cloneProject(tracedProject);

    TraceValidatingCommitValidationListener commitValidationListener =
        new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), tracedRepo);
      PushOneCommit.Result r = push.to("refs/for/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).contains("issue123");
      assertThat(commitValidationListener.isLoggingForced).isTrue();
      assertThat(commitValidationListener.tags.get("project")).containsExactly(tracedProject.get());
    }

    // other project is not traced
    commitValidationListener = new TraceValidatingCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(commitValidationListener)) {
      PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
      PushOneCommit.Result r = push.to("refs/for/master");
      r.assertOkStatus();
      assertThat(commitValidationListener.traceIds).doesNotContain("issue123");
      assertThat(commitValidationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(commitValidationListener.tags.get("project")).containsExactly(project.get());
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestType", value = "FOO")
  public void traceProjectInvalidRequestType() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new22");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new22");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "1000000")
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new.*")
  public void traceProjectForAccount() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new23");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new23");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "1000000")
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "foo.*")
  public void traceProjectForAccountNoProjectMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new24");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new24");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.account", value = "1000001")
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "new.*")
  public void traceProjectForAccountNoAccountMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new25");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new25");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/projects/.*")
  public void traceRequestUri() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new26");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new26");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/projects/.*/foo")
  public void traceRequestUriNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new27");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new27");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "][")
  public void traceRequestUriInvalidRegEx() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/new28");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("new28");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "/projects/.*")
  public void traceExcludedRequestUriPattern() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz1");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz1");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "/projects/no-match")
  public void traceConfigWithExcludedRequestUriPatternOnlyDoesntTriggerTracing() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz2");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz2");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.projectPattern", value = "xyz3")
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "/projects/no-match")
  public void traceExcludedRequestUriPatternNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz3");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz3");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/projects/.*")
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "/projects/xyz4")
  public void traceRequestUriPatternAndExcludedRequestUriPattern() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz4");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz4");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/projects/.*")
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "/projects/no-match")
  public void traceRequestUriPatternAndExcludedRequestUriPatternNoMatch() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz5");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).contains("issue123");
      assertThat(projectCreationListener.isLoggingForced).isTrue();
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz5");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.excludedRequestUriPattern", value = "][")
  public void traceExcludedRequestUriInvalidRegEx() throws Exception {
    TraceValidatingProjectCreationValidationListener projectCreationListener =
        new TraceValidatingProjectCreationValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(projectCreationListener)) {
      RestResponse response = adminRestSession.put("/projects/xyz6");
      assertThat(response.getStatusCode()).isEqualTo(SC_CREATED);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(projectCreationListener.traceIds).doesNotContain("issue123");
      assertThat(projectCreationListener.isLoggingForced).isFalse();

      // The logging tag with the project name is also set if tracing is off.
      assertThat(projectCreationListener.tags.get("project")).containsExactly("xyz6");
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestQueryStringPattern", value = ".*limit=.*")
  public void traceConfigWithRequestQueryStringOnlyDoesntTriggerTracing() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.get(String.format("/changes/%s/suggest_reviewers?limit=10", changeId));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestQueryStringPattern", value = ".*limit=.*")
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/changes/.*")
  public void traceRequestQueryString() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.get(String.format("/changes/%s/suggest_reviewers?limit=10", changeId));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).contains("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isTrue();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestQueryStringPattern", value = ".*query=.*")
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/changes/.*")
  public void traceRequestQueryStringNoMatch() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.get(String.format("/changes/%s/suggest_reviewers?limit=10", changeId));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.requestQueryStringPattern", value = "][")
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/changes/.*")
  public void traceRequestQueryStringInvalidRegEx() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.get(String.format("/changes/%s/suggest_reviewers?limit=10", changeId));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.headerPattern", value = "User-Agent=foo.*")
  public void traceConfigWithHeaderOnlyDoesntTriggerTracing() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {

      RestResponse response =
          adminRestSession.getWithHeaders(
              String.format("/changes/%s/suggest_reviewers?limit=10", changeId),
              new BasicHeader("User-Agent", "foo-bar"),
              new BasicHeader("Other-Header", "baz"));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.headerPattern", value = "User-Agent=foo.*")
  @GerritConfig(name = "tracing.issue123.requestUriPattern", value = "/changes/.*")
  public void traceHeader() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {

      RestResponse response =
          adminRestSession.getWithHeaders(
              String.format("/changes/%s/suggest_reviewers?limit=10", changeId),
              new BasicHeader("User-Agent", "foo-bar"),
              new BasicHeader("Other-Header", "baz"));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).contains("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isTrue();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.headerPattern", value = "User-Agent=bar.*")
  public void traceHeaderNoMatch() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.getWithHeaders(
              String.format("/changes/%s/suggest_reviewers?limit=10", changeId),
              new BasicHeader("User-Agent", "foo-bar"),
              new BasicHeader("Other-Header", "baz"));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "tracing.issue123.headerPattern", value = "][")
  public void traceHeaderInvalidRegEx() throws Exception {
    String changeId = createChange().getChangeId();
    TraceReviewerSuggestion reviewerSuggestion = new TraceReviewerSuggestion();
    try (Registration registration =
        extensionRegistry.newRegistration().add(reviewerSuggestion, /* exportName= */ "foo")) {
      RestResponse response =
          adminRestSession.getWithHeaders(
              String.format("/changes/%s/suggest_reviewers?limit=10", changeId),
              new BasicHeader("User-Agent", "foo-bar"),
              new BasicHeader("Other-Header", "baz"));
      assertThat(response.getStatusCode()).isEqualTo(SC_OK);
      assertThat(response.getHeaders(RestApiServlet.X_GERRIT_TRACE)).doesNotContain("issue123");
      assertThat(reviewerSuggestion.traceIds).doesNotContain("issue123");
      assertThat(reviewerSuggestion.isLoggingForced).isFalse();
    }
  }

  @Test
  @GerritConfig(name = "retry.retryWithTraceOnFailure", value = "true")
  public void listenOnRetries() throws Exception {
    String changeId = createChange().getChangeId();
    approve(changeId);

    TraceSubmitRule traceSubmitRule = new TraceSubmitRule();
    traceSubmitRule.failAlways = true;
    TestRetryListener testRetryListener = new TestRetryListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(traceSubmitRule).add(testRetryListener)) {
      RestResponse response = adminRestSession.post("/changes/" + changeId + "/submit");
      assertThat(response.getStatusCode()).isEqualTo(SC_INTERNAL_SERVER_ERROR);

      TestRetryListener.Retry retry = testRetryListener.getOnlyRetry();
      assertThat(retry.actionType()).isEqualTo(ActionType.REST_WRITE_REQUEST.name());
      assertThat(retry.actionName()).isEqualTo("restapi.change.Submit.CurrentRevision");
      assertThat(retry.nextAttempt()).isEqualTo(2);
      assertThat(retry.cause()).isEqualTo(TraceSubmitRule.FAILURE);
    }
  }

  @Test
  @GerritConfig(name = "retry.retryWithTraceOnFailure", value = "true")
  public void autoRetryWithTrace() throws Exception {
    String changeId = createChange().getChangeId();
    approve(changeId);

    TraceSubmitRule traceSubmitRule = new TraceSubmitRule();
    traceSubmitRule.failAlways = true;
    try (Registration registration = extensionRegistry.newRegistration().add(traceSubmitRule)) {
      RestResponse response = adminRestSession.post("/changes/" + changeId + "/submit");
      assertThat(response.getStatusCode()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
      assertWithMessage(
              "headers: %s do not contain a 'retry-on-failure' header",
              response.getHeaders(RestApiServlet.X_GERRIT_TRACE))
          .that(
              response.getHeaders(RestApiServlet.X_GERRIT_TRACE).stream()
                  .anyMatch(h -> h.startsWith("retry-on-failure-")))
          .isTrue();
      assertWithMessage(
              "traceSubmitRule.traceIds: %s do not contain a 'retry-on-failure-' trace ID",
              traceSubmitRule.traceIds)
          .that(
              traceSubmitRule.traceIds.stream().anyMatch(id -> id.startsWith("retry-on-failure-")))
          .isTrue();
      assertThat(traceSubmitRule.isLoggingForced).isTrue();
    }
  }

  @Test
  @GerritConfig(name = "retry.timeout", value = "1s")
  @GerritConfig(name = "retry.retryWithTraceOnFailure", value = "true")
  public void noAutoRetryIfExceptionCausesNormalRetrying() throws Exception {
    String changeId = createChange().getChangeId();
    approve(changeId);

    TraceSubmitRule traceSubmitRule = new TraceSubmitRule();
    traceSubmitRule.failAlways = true;
    try (Registration registration =
        extensionRegistry
            .newRegistration()
            .add(traceSubmitRule)
            .add(
                new ExceptionHook() {
                  @Override
                  public boolean shouldRetry(String actionType, String actionName, Throwable t) {
                    return true;
                  }
                })) {
      RestResponse response = adminRestSession.post("/changes/" + changeId + "/submit");
      assertThat(response.getStatusCode()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
      assertThat(
              response.getHeaders(RestApiServlet.X_GERRIT_TRACE).stream()
                  .noneMatch(id -> id.startsWith("retry-on-failure-")))
          .isTrue();
      assertThat(
              traceSubmitRule.traceIds.stream().noneMatch(id -> id.startsWith("retry-on-failure-")))
          .isTrue();
      assertThat(traceSubmitRule.isLoggingForced).isFalse();
    }
  }

  @Test
  public void noAutoRetryWithTraceIfDisabled() throws Exception {
    String changeId = createChange().getChangeId();
    approve(changeId);

    TraceSubmitRule traceSubmitRule = new TraceSubmitRule();
    traceSubmitRule.failOnce = true;
    try (Registration registration = extensionRegistry.newRegistration().add(traceSubmitRule)) {
      RestResponse response = adminRestSession.post("/changes/" + changeId + "/submit");
      assertThat(response.getStatusCode()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
      assertThat(
              response.getHeaders(RestApiServlet.X_GERRIT_TRACE).stream()
                  .noneMatch(id -> id.startsWith("retry-on-failure-")))
          .isTrue();
      assertThat(
              traceSubmitRule.traceIds.stream().noneMatch(id -> id.startsWith("retry-on-failure-")))
          .isTrue();
      assertThat(traceSubmitRule.isLoggingForced).isFalse();
    }
  }

  private void assertForceLogging(boolean expected) {
    assertThat(LoggingContext.getInstance().shouldForceLogging(null, null, false))
        .isEqualTo(expected);
  }

  private static class TraceValidatingProjectCreationValidationListener
      implements ProjectCreationValidationListener {
    ImmutableSet<String> traceIds;
    Boolean isLoggingForced;
    ImmutableSetMultimap<String, String> tags;

    @Override
    public void validateNewProject(CreateProjectArgs args) throws ValidationException {
      this.traceIds = LoggingContext.getInstance().getTagsAsMap().get("TRACE_ID");
      this.isLoggingForced = LoggingContext.getInstance().shouldForceLogging(null, null, false);
      this.tags = LoggingContext.getInstance().getTagsAsMap();
    }
  }

  private static class TraceValidatingCommitValidationListener implements CommitValidationListener {
    ImmutableSet<String> traceIds;
    Boolean isLoggingForced;
    ImmutableSetMultimap<String, String> tags;

    @Override
    public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
        throws CommitValidationException {
      this.traceIds = LoggingContext.getInstance().getTagsAsMap().get("TRACE_ID");
      this.isLoggingForced = LoggingContext.getInstance().shouldForceLogging(null, null, false);
      this.tags = LoggingContext.getInstance().getTagsAsMap();
      return ImmutableList.of();
    }
  }

  private static class TraceReviewerSuggestion implements ReviewerSuggestion {
    ImmutableSet<String> traceIds;
    Boolean isLoggingForced;

    @Override
    public Set<SuggestedReviewer> suggestReviewers(
        Project.NameKey project,
        Change.Id changeId,
        String query,
        Set<com.google.gerrit.entities.Account.Id> candidates) {
      this.traceIds = LoggingContext.getInstance().getTagsAsMap().get("TRACE_ID");
      this.isLoggingForced = LoggingContext.getInstance().shouldForceLogging(null, null, false);
      return ImmutableSet.of();
    }
  }

  private static class TraceChangeIndexedListener implements ChangeIndexedListener {
    ImmutableSetMultimap<String, String> tags;

    @Override
    public void onChangeIndexed(String projectName, int id) {
      this.tags = LoggingContext.getInstance().getTagsAsMap();
    }

    @Override
    public void onChangeDeleted(int id) {}
  }

  private static class TraceSubmitRule implements SubmitRule {
    static final RuntimeException FAILURE = new IllegalStateException("forced failure from test");

    ImmutableSet<String> traceIds;
    Boolean isLoggingForced;
    boolean failOnce;
    boolean failAlways;

    @Override
    public Optional<SubmitRecord> evaluate(ChangeData changeData) {
      this.traceIds = LoggingContext.getInstance().getTagsAsMap().get("TRACE_ID");
      this.isLoggingForced = LoggingContext.getInstance().shouldForceLogging(null, null, false);

      if (failOnce || failAlways) {
        failOnce = false;
        throw FAILURE;
      }

      SubmitRecord submitRecord = new SubmitRecord();
      submitRecord.status = SubmitRecord.Status.OK;
      return Optional.of(submitRecord);
    }
  }

  @AutoValue
  abstract static class PerformanceLogEntry {
    static PerformanceLogEntry create(String operation, Metadata metadata) {
      return new AutoValue_TraceIT_PerformanceLogEntry(operation, metadata);
    }

    abstract String operation();

    abstract Metadata metadata();
  }
}
