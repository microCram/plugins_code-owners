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
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.projects.ConfigInput;
import com.google.gerrit.extensions.client.ProjectState;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersIT;
import com.google.gerrit.plugins.codeowners.api.CodeOwnerProjectConfigInfo;
import com.google.gerrit.plugins.codeowners.api.MergeCommitStrategy;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerBackend;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerBackendId;
import com.google.gerrit.plugins.codeowners.config.BackendConfig;
import com.google.gerrit.plugins.codeowners.config.CodeOwnersPluginConfiguration;
import com.google.gerrit.plugins.codeowners.config.GeneralConfig;
import com.google.gerrit.plugins.codeowners.config.OverrideApprovalConfig;
import com.google.gerrit.plugins.codeowners.config.RequiredApprovalConfig;
import com.google.gerrit.plugins.codeowners.config.StatusConfig;
import com.google.inject.Inject;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

/**
 * Acceptance test for the {@link
 * com.google.gerrit.plugins.codeowners.restapi.GetCodeOwnerProjectConfig} REST endpoint.
 *
 * <p>Further tests for the {@link
 * com.google.gerrit.plugins.codeowners.restapi.GetCodeOwnerProjectConfig} REST endpoint that
 * require using the REST API are implemented in {@link
 * com.google.gerrit.plugins.codeowners.acceptance.restapi.GetCodeOwnerProjectConfigRestIT}.
 */
public class GetCodeOwnerProjectConfigIT extends AbstractCodeOwnersIT {
  @Inject private ProjectOperations projectOperations;

  private BackendConfig backendConfig;

  @Before
  public void setup() throws Exception {
    backendConfig = plugin.getSysInjector().getInstance(BackendConfig.class);
  }

  @Test
  public void cannotGetConfigForHiddenProject() throws Exception {
    ConfigInput configInput = new ConfigInput();
    configInput.state = ProjectState.HIDDEN;
    gApi.projects().name(project.get()).config(configInput);

    ResourceConflictException exception =
        assertThrows(
            ResourceConflictException.class,
            () -> projectCodeOwnersApiFactory.project(project).getConfig());
    assertThat(exception).hasMessageThat().isEqualTo("project state HIDDEN does not permit read");
  }

  @Test
  public void getDefaultConfig() throws Exception {
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.general.fileExtension).isNull();
    assertThat(codeOwnerProjectConfigInfo.general.mergeCommitStrategy)
        .isEqualTo(MergeCommitStrategy.ALL_CHANGED_FILES);
    assertThat(codeOwnerProjectConfigInfo.general.implicitApprovals).isNull();
    assertThat(codeOwnerProjectConfigInfo.general.overrideInfoUrl).isNull();
    assertThat(codeOwnerProjectConfigInfo.status.disabled).isNull();
    assertThat(codeOwnerProjectConfigInfo.status.disabledBranches).isNull();
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch).isNull();
    assertThat(codeOwnerProjectConfigInfo.backend.id)
        .isEqualTo(CodeOwnerBackendId.getBackendId(backendConfig.getDefaultBackend().getClass()));
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch).isNull();
    assertThat(codeOwnerProjectConfigInfo.requiredApproval.label)
        .isEqualTo(RequiredApprovalConfig.DEFAULT_LABEL);
    assertThat(codeOwnerProjectConfigInfo.requiredApproval.value)
        .isEqualTo(RequiredApprovalConfig.DEFAULT_VALUE);
    assertThat(codeOwnerProjectConfigInfo.overrideApproval).isNull();
  }

  @Test
  public void getConfigWithConfiguredFileExtension() throws Exception {
    configureFileExtension(project, "foo");
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.general.fileExtension).isEqualTo("foo");
  }

  @Test
  public void getConfigWithConfiguredOverrideInfoUrl() throws Exception {
    configureOverrideInfoUrl(project, "http://foo.example.com");
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.general.overrideInfoUrl)
        .isEqualTo("http://foo.example.com");
  }

  @Test
  public void getConfigWithConfiguredMergeCommitStrategy() throws Exception {
    configureMergeCommitStrategy(project, MergeCommitStrategy.FILES_WITH_CONFLICT_RESOLUTION);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.general.mergeCommitStrategy)
        .isEqualTo(MergeCommitStrategy.FILES_WITH_CONFLICT_RESOLUTION);
  }

  @Test
  public void getConfigForDisabledProject() throws Exception {
    disableCodeOwnersForProject(project);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.status.disabled).isTrue();
    assertThat(codeOwnerProjectConfigInfo.status.disabledBranches).isNull();
    assertThat(codeOwnerProjectConfigInfo.general).isNull();
    assertThat(codeOwnerProjectConfigInfo.backend).isNull();
    assertThat(codeOwnerProjectConfigInfo.requiredApproval).isNull();
    assertThat(codeOwnerProjectConfigInfo.overrideApproval).isNull();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.disabled", value = "true")
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "INVALID")
  public void getConfigForDisabledProject_invalidPluginConfig() throws Exception {
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.status.disabled).isTrue();
    assertThat(codeOwnerProjectConfigInfo.status.disabledBranches).isNull();
    assertThat(codeOwnerProjectConfigInfo.general).isNull();
    assertThat(codeOwnerProjectConfigInfo.backend).isNull();
    assertThat(codeOwnerProjectConfigInfo.requiredApproval).isNull();
    assertThat(codeOwnerProjectConfigInfo.overrideApproval).isNull();
  }

  @Test
  public void getConfigWithDisabledBranch() throws Exception {
    configureDisabledBranch(project, "refs/heads/master");
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.status.disabled).isNull();
    assertThat(codeOwnerProjectConfigInfo.status.disabledBranches)
        .containsExactly("refs/heads/master");
  }

  @Test
  public void getConfigWithConfiguredBackend() throws Exception {
    String otherBackendId = getOtherCodeOwnerBackend(backendConfig.getDefaultBackend());
    configureBackend(project, otherBackendId);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.backend.id).isEqualTo(otherBackendId);
  }

  @Test
  public void getConfigWithConfiguredBranchSpecificBackend() throws Exception {
    String otherBackendId = getOtherCodeOwnerBackend(backendConfig.getDefaultBackend());
    configureBackend(project, "master", otherBackendId);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.backend.id).isNotEqualTo(otherBackendId);
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch)
        .containsExactly("refs/heads/master", otherBackendId);
  }

  @Test
  public void branchSpecificBackendIsOmittedIfItMatchesTheRepositoryBackend() throws Exception {
    String backendId =
        CodeOwnerBackendId.getBackendId(backendConfig.getDefaultBackend().getClass());
    configureBackend(project, "master", backendId);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.backend.id).isEqualTo(backendId);
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch).isNull();
  }

  @Test
  public void branchSpecificBackendIsOmittedForNonExistingBranch() throws Exception {
    String otherBackendId = getOtherCodeOwnerBackend(backendConfig.getDefaultBackend());
    configureBackend(project, "non-existing", otherBackendId);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.backend.id).isNotEqualTo(otherBackendId);
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch).isNull();
  }

  @Test
  public void branchSpecificBackendIsOmittedForNonVisibleBranch() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();

    String otherBackendId = getOtherCodeOwnerBackend(backendConfig.getDefaultBackend());
    configureBackend(project, "master", otherBackendId);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.backend.id).isNotEqualTo(otherBackendId);
    assertThat(codeOwnerProjectConfigInfo.backend.idsByBranch).isNull();
  }

  @Test
  public void getConfigWithConfiguredRequiredApproval() throws Exception {
    configureRequiredApproval(project, "Code-Review+2");
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.requiredApproval.label).isEqualTo("Code-Review");
    assertThat(codeOwnerProjectConfigInfo.requiredApproval.value).isEqualTo(2);
  }

  @Test
  public void getConfigWithConfiguredOverrideApproval() throws Exception {
    configureImplicitApprovals(project);
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.general.implicitApprovals).isTrue();
  }

  @Test
  public void getConfigWithEnabledImplicitApprovals() throws Exception {
    configureOverrideApproval(project, "Code-Review+2");
    CodeOwnerProjectConfigInfo codeOwnerProjectConfigInfo =
        projectCodeOwnersApiFactory.project(project).getConfig();
    assertThat(codeOwnerProjectConfigInfo.overrideApproval.label).isEqualTo("Code-Review");
    assertThat(codeOwnerProjectConfigInfo.overrideApproval.value).isEqualTo(2);
  }

  private void configureFileExtension(Project.NameKey project, String fileExtension)
      throws Exception {
    setConfig(project, null, GeneralConfig.KEY_FILE_EXTENSION, fileExtension);
  }

  private void configureOverrideInfoUrl(Project.NameKey project, String overrideInfoUrl)
      throws Exception {
    setConfig(project, null, GeneralConfig.KEY_OVERRIDE_INFO_URL, overrideInfoUrl);
  }

  private void configureMergeCommitStrategy(
      Project.NameKey project, MergeCommitStrategy mergeCommitStrategy) throws Exception {
    setConfig(project, null, GeneralConfig.KEY_MERGE_COMMIT_STRATEGY, mergeCommitStrategy.name());
  }

  private void configureDisabledBranch(Project.NameKey project, String disabledBranch)
      throws Exception {
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED_BRANCH, disabledBranch);
  }

  private void configureBackend(Project.NameKey project, String backendName) throws Exception {
    configureBackend(project, null, backendName);
  }

  private void configureBackend(
      Project.NameKey project, @Nullable String branch, String backendName) throws Exception {
    setConfig(project, branch, BackendConfig.KEY_BACKEND, backendName);
  }

  private void configureRequiredApproval(Project.NameKey project, String requiredApproval)
      throws Exception {
    setConfig(project, null, RequiredApprovalConfig.KEY_REQUIRED_APPROVAL, requiredApproval);
  }

  private void configureOverrideApproval(Project.NameKey project, String overrideApproval)
      throws Exception {
    setConfig(project, null, OverrideApprovalConfig.KEY_OVERRIDE_APPROVAL, overrideApproval);
  }

  private void configureImplicitApprovals(Project.NameKey project) throws Exception {
    setConfig(project, null, GeneralConfig.KEY_ENABLE_IMPLICIT_APPROVALS, "true");
  }

  private void setConfig(Project.NameKey project, String subsection, String key, String value)
      throws Exception {
    Config codeOwnersConfig = new Config();
    codeOwnersConfig.setString(
        CodeOwnersPluginConfiguration.SECTION_CODE_OWNERS, subsection, key, value);
    try (TestRepository<Repository> testRepo =
        new TestRepository<>(repoManager.openRepository(project))) {
      Ref ref = testRepo.getRepository().exactRef(RefNames.REFS_CONFIG);
      RevCommit head = testRepo.getRevWalk().parseCommit(ref.getObjectId());
      testRepo.update(
          RefNames.REFS_CONFIG,
          testRepo
              .commit()
              .parent(head)
              .message("Configure code owner backend")
              .add("code-owners.config", codeOwnersConfig.toText()));
    }
    projectCache.evict(project);
  }

  /** Returns the ID of a code owner backend that is not the given backend. */
  private String getOtherCodeOwnerBackend(CodeOwnerBackend codeOwnerBackend) {
    for (CodeOwnerBackendId codeOwnerBackendId : CodeOwnerBackendId.values()) {
      if (!codeOwnerBackendId.getCodeOwnerBackendClass().equals(codeOwnerBackend.getClass())) {
        return codeOwnerBackendId.getBackendId();
      }
    }
    throw new IllegalStateException(
        String.format("couldn't find other backend than %s", codeOwnerBackend.getClass()));
  }
}
