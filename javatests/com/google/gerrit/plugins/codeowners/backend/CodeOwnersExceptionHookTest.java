// Copyright (C) 2021 The Android Open Source Project
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

package com.google.gerrit.plugins.codeowners.backend;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.truth.OptionalSubject.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersTest;
import com.google.gerrit.plugins.codeowners.backend.config.InvalidPluginConfigurationException;
import com.google.gerrit.server.ExceptionHook.Status;
import java.nio.file.InvalidPathException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link CodeOwnersExceptionHook}. */
public class CodeOwnersExceptionHookTest extends AbstractCodeOwnersTest {
  private CodeOwnersExceptionHook codeOwnersExceptionHook;

  @Before
  public void setUpCodeOwnersPlugin() throws Exception {
    codeOwnersExceptionHook = plugin.getSysInjector().getInstance(CodeOwnersExceptionHook.class);
  }

  @Test
  public void skipRetryWithTrace() throws Exception {
    assertThat(skipRetryWithTrace(newInvalidPluginConfigurationException())).isTrue();
    assertThat(skipRetryWithTrace(newExceptionWithCause(newInvalidPluginConfigurationException())))
        .isTrue();

    assertThat(skipRetryWithTrace(newInvalidCodeOwnerConfigException())).isTrue();
    assertThat(skipRetryWithTrace(newExceptionWithCause(newInvalidCodeOwnerConfigException())))
        .isTrue();

    assertThat(skipRetryWithTrace(newInvalidPathException())).isTrue();
    assertThat(skipRetryWithTrace(newExceptionWithCause(newInvalidPathException()))).isTrue();

    assertThat(
            skipRetryWithTrace(
                CodeOwnersInternalServerErrorException.newInternalServerError("msg")))
        .isFalse();
    assertThat(
            skipRetryWithTrace(
                newExceptionWithCause(
                    CodeOwnersInternalServerErrorException.newInternalServerError("msg"))))
        .isFalse();

    assertThat(skipRetryWithTrace(new Exception())).isFalse();
    assertThat(skipRetryWithTrace(newExceptionWithCause(new Exception()))).isFalse();
  }

  @Test
  public void getUserMessages() throws Exception {
    InvalidPluginConfigurationException invalidPluginConfigurationException =
        newInvalidPluginConfigurationException();
    assertThat(getUserMessages(invalidPluginConfigurationException))
        .containsExactly(invalidPluginConfigurationException.getMessage());
    assertThat(getUserMessages(newExceptionWithCause(invalidPluginConfigurationException)))
        .containsExactly(invalidPluginConfigurationException.getMessage());

    InvalidCodeOwnerConfigException invalidCodeOwnerConfigException =
        newInvalidCodeOwnerConfigException();
    assertThat(getUserMessages(invalidCodeOwnerConfigException))
        .containsExactly(invalidCodeOwnerConfigException.getMessage());
    assertThat(getUserMessages(newExceptionWithCause(invalidCodeOwnerConfigException)))
        .containsExactly(invalidCodeOwnerConfigException.getMessage());

    InvalidPathException invalidPathException = newInvalidPathException();
    assertThat(getUserMessages(invalidPathException))
        .containsExactly(invalidPathException.getMessage());
    assertThat(getUserMessages(newExceptionWithCause(invalidPathException)))
        .containsExactly(invalidPathException.getMessage());

    CodeOwnersInternalServerErrorException codeOwnersInternalServerErrorException =
        CodeOwnersInternalServerErrorException.newInternalServerError("msg");
    assertThat(getUserMessages(codeOwnersInternalServerErrorException))
        .containsExactly(codeOwnersInternalServerErrorException.getUserVisibleMessage());
    assertThat(getUserMessages(newExceptionWithCause(codeOwnersInternalServerErrorException)))
        .containsExactly(codeOwnersInternalServerErrorException.getUserVisibleMessage());

    assertThat(getUserMessages(new Exception())).isEmpty();
    assertThat(getUserMessages(newExceptionWithCause(new Exception()))).isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.invalidCodeOwnerConfigInfoUrl", value = "http://foo.bar")
  public void getUserMessages_withInvalidCodeOwnerConfigInfoUrl() throws Exception {
    InvalidCodeOwnerConfigException invalidCodeOwnerConfigException =
        newInvalidCodeOwnerConfigException();
    assertThat(getUserMessages(invalidCodeOwnerConfigException))
        .containsExactly(
            invalidCodeOwnerConfigException.getMessage(), "For help check http://foo.bar")
        .inOrder();
    assertThat(getUserMessages(newExceptionWithCause(invalidCodeOwnerConfigException)))
        .containsExactly(
            invalidCodeOwnerConfigException.getMessage(), "For help check http://foo.bar")
        .inOrder();
  }

  @Test
  public void getStatus() throws Exception {
    Status conflictStatus = Status.create(409, "Conflict");
    assertThat(getStatus(newInvalidPluginConfigurationException()))
        .value()
        .isEqualTo(conflictStatus);
    assertThat(getStatus(newExceptionWithCause(newInvalidPluginConfigurationException())))
        .value()
        .isEqualTo(conflictStatus);

    assertThat(getStatus(newInvalidCodeOwnerConfigException())).value().isEqualTo(conflictStatus);
    assertThat(getStatus(newExceptionWithCause(newInvalidCodeOwnerConfigException())))
        .value()
        .isEqualTo(conflictStatus);

    assertThat(getStatus(newInvalidPathException())).value().isEqualTo(conflictStatus);
    assertThat(getStatus(newExceptionWithCause(newInvalidPathException())))
        .value()
        .isEqualTo(conflictStatus);

    assertThat(getStatus(new Exception())).isEmpty();
    assertThat(getStatus(newExceptionWithCause(new Exception()))).isEmpty();

    assertThat(getStatus(CodeOwnersInternalServerErrorException.newInternalServerError("msg")))
        .isEmpty();
    assertThat(
            getStatus(
                newExceptionWithCause(
                    CodeOwnersInternalServerErrorException.newInternalServerError("msg"))))
        .isEmpty();
  }

  private boolean skipRetryWithTrace(Exception exception) {
    return codeOwnersExceptionHook.skipRetryWithTrace("actionType", "actionName", exception);
  }

  private ImmutableList<String> getUserMessages(Exception exception) {
    return codeOwnersExceptionHook.getUserMessages(exception, /* traceId= */ null);
  }

  private Optional<Status> getStatus(Exception exception) {
    return codeOwnersExceptionHook.getStatus(exception);
  }

  private Exception newExceptionWithCause(Exception cause) {
    return new Exception("exception1", new Exception("exception2", cause));
  }

  private InvalidPluginConfigurationException newInvalidPluginConfigurationException() {
    return new InvalidPluginConfigurationException("code-owners", "message");
  }

  private InvalidCodeOwnerConfigException newInvalidCodeOwnerConfigException() {
    return new InvalidCodeOwnerConfigException("message", project, "refs/heads/master", "/OWNERS");
  }

  private InvalidPathException newInvalidPathException() {
    return new InvalidPathException("input", "reason");
  }
}
