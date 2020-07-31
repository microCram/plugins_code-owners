// Copyright (C) 2020 The Android Open Source Project
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

package com.google.gerrit.plugins.codeowners.acceptance.api;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerInfoSubject.hasAccountId;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.plugins.codeowners.api.CodeOwnerInfo;
import com.google.gerrit.plugins.codeowners.api.CodeOwners;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerSetModification;
import com.google.inject.Inject;
import java.util.List;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

/**
 * Acceptance test for the {@link
 * com.google.gerrit.plugins.codeowners.restapi.GetCodeOwnersForPathInBranch} REST endpoint.
 *
 * <p>Further tests for the {@link
 * com.google.gerrit.plugins.codeowners.restapi.GetCodeOwnersForPathInBranch} REST endpoint that
 * require using the REST API are implemented in {@link
 * com.google.gerrit.plugins.codeowners.acceptance.restapi.GetCodeOwnersForPathInBranchRestIT}.
 */
public class GetCodeOwnersForPathInBranchIT extends AbstractGetCodeOwnersForPathIT {
  @Inject private ProjectOperations projectOperations;

  @Override
  protected CodeOwners getCodeOwnersApi() throws RestApiException {
    return codeOwnersApiFactory.branch(project, "master");
  }

  @Test
  public void getCodeOwnersForRevision() throws Exception {
    // Create an initial code owner config that only has 'admin' as code owner.
    CodeOwnerConfig.Key codeOwnerConfigKey =
        codeOwnerConfigOperations
            .newCodeOwnerConfig()
            .project(project)
            .branch("master")
            .folderPath("/foo/bar/")
            .addCodeOwnerEmail(admin.email())
            .create();
    RevCommit revision1 = projectOperations.project(project).getHead("master");

    // Update the code owner config so that also 'user' is a code owner now.
    codeOwnerConfigOperations
        .codeOwnerConfig(codeOwnerConfigKey)
        .forUpdate()
        .codeOwnerSetsModification(CodeOwnerSetModification.addToOnlySet(user.email()))
        .update();
    RevCommit revision2 = projectOperations.project(project).getHead("master");
    assertThat(revision1).isNotEqualTo(revision2);

    // For the first revision we expect that only 'admin' is returned as code owner.
    List<CodeOwnerInfo> codeOwnerInfos =
        queryCodeOwners(
            getCodeOwnersApi().query().forRevision(revision1.name()), "/foo/bar/baz.md");
    assertThat(codeOwnerInfos).comparingElementsUsing(hasAccountId()).containsExactly(admin.id());

    // For the second revision we expect that 'admin' and 'user' are returned as code owners.
    codeOwnerInfos =
        queryCodeOwners(
            getCodeOwnersApi().query().forRevision(revision2.name()), "/foo/bar/baz.md");
    assertThat(codeOwnerInfos)
        .comparingElementsUsing(hasAccountId())
        .containsExactly(admin.id(), user.id());
  }

  @Test
  public void cannotGetCodeOwnersForInvalidRevision() throws Exception {
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                queryCodeOwners(
                    getCodeOwnersApi().query().forRevision("INVALID"), "/foo/bar/baz.md"));
    assertThat(exception).hasMessageThat().isEqualTo("invalid revision");
  }

  @Test
  public void cannotGetCodeOwnersForUnknownRevision() throws Exception {
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                queryCodeOwners(
                    getCodeOwnersApi()
                        .query()
                        .forRevision("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"),
                    "/foo/bar/baz.md"));
    assertThat(exception).hasMessageThat().isEqualTo("unknown revision");
  }

  @Test
  public void cannotGetCodeOwnersForRevisionOfOtherBranch() throws Exception {
    RevCommit revision = projectOperations.project(project).getHead(RefNames.REFS_CONFIG);
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                queryCodeOwners(
                    getCodeOwnersApi().query().forRevision(revision.name()), "/foo/bar/baz.md"));
    assertThat(exception).hasMessageThat().isEqualTo("unknown revision");
  }
}
