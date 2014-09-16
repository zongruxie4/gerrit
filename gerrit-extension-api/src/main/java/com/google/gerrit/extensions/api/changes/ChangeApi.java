// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.extensions.api.changes;

import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ListChangesOption;
import com.google.gerrit.extensions.restapi.NotImplementedException;
import com.google.gerrit.extensions.restapi.RestApiException;

import java.util.EnumSet;

public interface ChangeApi {
  String id();

  /**
   * Look up the current revision for the change.
   * <p>
   * <strong>Note:</strong> This method eagerly reads the revision. Methods that
   * mutate the revision do not necessarily re-read the revision. Therefore,
   * calling a getter method on an instance after calling a mutation method on
   * that same instance is not guaranteed to reflect the mutation. It is not
   * recommended to store references to {@code RevisionApi} instances.
   *
   * @return API for accessing the revision.
   * @throws RestApiException if an error occurred.
   */
  RevisionApi current() throws RestApiException;

  /**
   * Look up a revision of a change by number.
   *
   * @see #current()
   */
  RevisionApi revision(int id) throws RestApiException;

  /**
   * Look up a revision of a change by commit SHA-1.
   *
   * @see #current()
   */
  RevisionApi revision(String id) throws RestApiException;

  void abandon() throws RestApiException;
  void abandon(AbandonInput in) throws RestApiException;

  void restore() throws RestApiException;
  void restore(RestoreInput in) throws RestApiException;

  /**
   * Create a new change that reverts this change.
   *
   * @see Changes#id(int)
   */
  ChangeApi revert() throws RestApiException;

  /**
   * Create a new change that reverts this change.
   *
   * @see Changes#id(int)
   */
  ChangeApi revert(RevertInput in) throws RestApiException;

  String topic() throws RestApiException;
  void topic(String topic) throws RestApiException;

  void addReviewer(AddReviewerInput in) throws RestApiException;
  void addReviewer(String in) throws RestApiException;

  ChangeInfo get(EnumSet<ListChangesOption> options) throws RestApiException;

  /** {@code get} with {@link ListChangesOption} set to ALL. */
  ChangeInfo get() throws RestApiException;
  /** {@code get} with {@link ListChangesOption} set to NONE. */
  ChangeInfo info() throws RestApiException;

  /**
   * Set hashtags on a change
   **/
  void setHashtags(HashtagsInput input) throws RestApiException;

  /**
   * A default implementation which allows source compatibility
   * when adding new methods to the interface.
   **/
  public class NotImplemented implements ChangeApi {
    @Override
    public String id() {
      throw new NotImplementedException();
    }

    @Override
    public RevisionApi current() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public RevisionApi revision(int id) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public RevisionApi revision(String id) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void abandon() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void abandon(AbandonInput in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void restore() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void restore(RestoreInput in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public ChangeApi revert() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public ChangeApi revert(RevertInput in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public String topic() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void topic(String topic) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void addReviewer(AddReviewerInput in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void addReviewer(String in) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public ChangeInfo get(EnumSet<ListChangesOption> options) throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public ChangeInfo get() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public ChangeInfo info() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public void setHashtags(HashtagsInput input) throws RestApiException {
      throw new NotImplementedException();
    }
  }
}
