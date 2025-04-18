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

package com.google.gerrit.extensions.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

public class GitPerson {
  public String name;
  public String email;

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  public Timestamp date;

  public int tz;

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  public void setDate(Instant when) {
    date = Timestamp.from(when);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof GitPerson)) {
      return false;
    }
    GitPerson p = (GitPerson) o;
    return Objects.equals(name, p.name)
        && Objects.equals(email, p.email)
        && Objects.equals(date, p.date)
        && tz == p.tz;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, email, date, tz);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{name="
        + name
        + ", email="
        + email
        + ", date="
        + date
        + ", tz="
        + tz
        + "}";
  }
}
