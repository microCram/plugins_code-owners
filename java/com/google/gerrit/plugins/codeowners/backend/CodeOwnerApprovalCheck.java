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

package com.google.gerrit.plugins.codeowners.backend;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.plugins.codeowners.api.CodeOwnerStatus;
import com.google.gerrit.plugins.codeowners.config.CodeOwnersPluginConfiguration;
import com.google.gerrit.plugins.codeowners.config.RequiredApproval;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.ReviewerStateInternal;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Class to check code owner approvals on a change.
 *
 * <p>Every file, or rather touched path, in a change needs to be approved by a code owner:
 *
 * <ul>
 *   <li>New file: requires approval on the new path
 *   <li>Modified file: requires approval on the old/new path (old path == new path)
 *   <li>Deleted file: requires approval on the old path
 *   <li>Renamed file: requires approval on the old and new path (equivalent to delete old path +
 *       add new path)
 *   <li>Copied file: requires approval on the new path (an approval on the old path is not needed
 *       since the file at this path was not touched)
 * </ul>
 */
@Singleton
public class CodeOwnerApprovalCheck {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PermissionBackend permissionBackend;
  private final GitRepositoryManager repoManager;
  private final CodeOwnersPluginConfiguration codeOwnersPluginConfiguration;
  private final ChangedFiles changedFiles;
  private final CodeOwnerConfigScanner codeOwnerConfigScanner;
  private final CodeOwnerConfigHierarchy codeOwnerConfigHierarchy;
  private final Provider<CodeOwnerResolver> codeOwnerResolver;

  @Inject
  CodeOwnerApprovalCheck(
      PermissionBackend permissionBackend,
      GitRepositoryManager repoManager,
      CodeOwnersPluginConfiguration codeOwnersPluginConfiguration,
      ChangedFiles changedFiles,
      CodeOwnerConfigScanner codeOwnerConfigScanner,
      CodeOwnerConfigHierarchy codeOwnerConfigHierarchy,
      Provider<CodeOwnerResolver> codeOwnerResolver) {
    this.permissionBackend = permissionBackend;
    this.repoManager = repoManager;
    this.codeOwnersPluginConfiguration = codeOwnersPluginConfiguration;
    this.changedFiles = changedFiles;
    this.codeOwnerConfigScanner = codeOwnerConfigScanner;
    this.codeOwnerConfigHierarchy = codeOwnerConfigHierarchy;
    this.codeOwnerResolver = codeOwnerResolver;
  }

  /**
   * Whether the given change has sufficient code owner approvals to be submittable.
   *
   * @param changeNotes the change notes
   * @return whether the given change has sufficient code owner approvals to be submittable
   */
  public boolean isSubmittable(ChangeNotes changeNotes) throws IOException {
    requireNonNull(changeNotes, "changeNotes");
    logger.atFine().log(
        "checking if change %d in project %s is submittable",
        changeNotes.getChangeId().get(), changeNotes.getProjectName());
    boolean isSubmittable =
        !getFileStatuses(changeNotes)
            .anyMatch(
                fileStatus ->
                    (fileStatus.newPathStatus().isPresent()
                            && fileStatus.newPathStatus().get().status()
                                != CodeOwnerStatus.APPROVED)
                        || (fileStatus.oldPathStatus().isPresent()
                            && fileStatus.oldPathStatus().get().status()
                                != CodeOwnerStatus.APPROVED));
    logger.atFine().log(
        "change %d in project %s %s submittable",
        changeNotes.getChangeId().get(),
        changeNotes.getProjectName(),
        isSubmittable ? "is" : "is not");
    return isSubmittable;
  }

  /**
   * Gets the code owner statuses for all files/paths that were changed in the current revision of
   * the given change.
   *
   * <p>The code owner statuses tell the user which code owner approvals are still missing in order
   * to make the change submittable.
   *
   * <p>Computing the code owner statuses for non-current revisions is not supported since the
   * semantics are unclear, e.g.:
   *
   * <ul>
   *   <li>non-current revisions are never submittable, hence asking which code owner approvals are
   *       still missing in order to make the revision submittable makes no sense
   *   <li>the code owner status {@link CodeOwnerStatus#PENDING} doesn't make sense for an old
   *       revision, from the perspective of the change owner this status looks like the change is
   *       waiting for the code owner to approve, but since voting on old revisions is not possible
   *       the code-owner is not able to provide this approval
   *   <li>the code owner statuses are computed from the approvals that were given by code owners,
   *       the UI always shows the current approval even when looking at an old revision, showing
   *       code owner statuses that mismatch the shown approvals (because they are computed from
   *       approvals that were present on an old revision) would only confuse users
   * </ul>
   *
   * @param changeNotes the notes of the change for which the current code owner statuses should be
   *     returned
   */
  public Stream<FileCodeOwnerStatus> getFileStatuses(ChangeNotes changeNotes) throws IOException {
    requireNonNull(changeNotes, "changeNotes");
    logger.atFine().log(
        "computing file statuses for change %d of project %s",
        changeNotes.getChangeId().get(), changeNotes.getProjectName());

    RequiredApproval requiredApproval =
        codeOwnersPluginConfiguration.getRequiredApproval(changeNotes.getProjectName());
    logger.atFine().log("requiredApproval = %s", requiredApproval);

    Optional<RequiredApproval> overrideApproval =
        codeOwnersPluginConfiguration.getOverrideApproval(changeNotes.getProjectName());
    boolean hasOverride =
        overrideApproval.isPresent() && hasOverride(overrideApproval.get(), changeNotes);
    logger.atFine().log("hasOverride = %s (overrideApproval = %s)", hasOverride, overrideApproval);

    BranchNameKey branch = changeNotes.getChange().getDest();
    ObjectId revision = getDestBranchRevision(changeNotes.getChange());
    logger.atFine().log("dest branch %s has revision %s", branch.branch(), revision.name());

    Account.Id patchSetUploader = changeNotes.getCurrentPatchSet().uploader();
    logger.atFine().log("patchSetUploader = %d", patchSetUploader.get());

    // If the branch doesn't contain any code owner config file yet, we apply special logic (project
    // owners count as code owners) to allow bootstrapping the code owner configuration in the
    // branch.
    boolean isBootstrapping = !codeOwnerConfigScanner.containsAnyCodeOwnerConfigFile(branch);
    logger.atFine().log("isBootstrapping = %s", isBootstrapping);

    ImmutableSet<Account.Id> reviewerAccountIds = getReviewerAccountIds(changeNotes);
    ImmutableSet<Account.Id> approverAccountIds =
        getApproverAccountIds(requiredApproval, changeNotes);
    logger.atFine().log("reviewers = %s, approvers = %s", reviewerAccountIds, approverAccountIds);

    return changedFiles
        .compute(changeNotes.getProjectName(), changeNotes.getCurrentPatchSet().commitId()).stream()
        .map(
            changedFile ->
                getFileStatus(
                    branch,
                    revision,
                    patchSetUploader,
                    reviewerAccountIds,
                    approverAccountIds,
                    hasOverride,
                    isBootstrapping,
                    changedFile));
  }

  private FileCodeOwnerStatus getFileStatus(
      BranchNameKey branch,
      ObjectId revision,
      Account.Id patchSetUploader,
      ImmutableSet<Account.Id> reviewerAccountIds,
      ImmutableSet<Account.Id> approverAccountIds,
      boolean hasOverride,
      boolean isBootstrapping,
      ChangedFile changedFile) {
    logger.atFine().log("computing file status for %s", changedFile);

    // Compute the code owner status for the new path, if there is a new path.
    Optional<PathCodeOwnerStatus> newPathStatus =
        changedFile
            .newPath()
            .map(
                newPath ->
                    getPathCodeOwnerStatus(
                        branch,
                        revision,
                        patchSetUploader,
                        reviewerAccountIds,
                        approverAccountIds,
                        hasOverride,
                        isBootstrapping,
                        newPath));

    // Compute the code owner status for the old path, if the file was deleted or renamed.
    Optional<PathCodeOwnerStatus> oldPathStatus = Optional.empty();
    if (changedFile.isDeletion() || changedFile.isRename()) {
      checkState(changedFile.oldPath().isPresent(), "old path must be present for deletion/rename");
      logger.atFine().log(
          "file was %s (old path = %s)",
          changedFile.isDeletion() ? "deleted" : "renamed", changedFile.oldPath().get());
      oldPathStatus =
          Optional.of(
              getPathCodeOwnerStatus(
                  branch,
                  revision,
                  patchSetUploader,
                  reviewerAccountIds,
                  approverAccountIds,
                  hasOverride,
                  isBootstrapping,
                  changedFile.oldPath().get()));
    }

    FileCodeOwnerStatus fileCodeOwnerStatus =
        FileCodeOwnerStatus.create(changedFile, newPathStatus, oldPathStatus);
    logger.atFine().log("fileCodeOwnerStatus = %s", fileCodeOwnerStatus);
    return fileCodeOwnerStatus;
  }

  private PathCodeOwnerStatus getPathCodeOwnerStatus(
      BranchNameKey branch,
      ObjectId revision,
      Account.Id patchSetUploader,
      ImmutableSet<Account.Id> reviewerAccountIds,
      ImmutableSet<Account.Id> approverAccountIds,
      boolean hasOverride,
      boolean isBootstrapping,
      Path absolutePath) {
    logger.atFine().log("computing path status for %s", absolutePath);

    if (hasOverride) {
      logger.atFine().log(
          "the status for path %s is %s since an override is present",
          absolutePath, CodeOwnerStatus.APPROVED.name());
      return PathCodeOwnerStatus.create(absolutePath, CodeOwnerStatus.APPROVED);
    }

    return isBootstrapping
        ? getPathCodeOwnerStatusBootstrappingMode(
            branch, patchSetUploader, reviewerAccountIds, approverAccountIds, absolutePath)
        : getPathCodeOwnerStatusRegularMode(
            branch,
            patchSetUploader,
            revision,
            reviewerAccountIds,
            approverAccountIds,
            absolutePath);
  }

  /**
   * Gets the code owner status for the given path when the branch doesn't contain any code owner
   * config file yet (bootstrapping mode).
   *
   * <p>If we are in bootstrapping mode we consider project owners as code owners. This allows
   * bootstrapping the code owner configuration in the branch.
   */
  private PathCodeOwnerStatus getPathCodeOwnerStatusBootstrappingMode(
      BranchNameKey branch,
      Account.Id patchSetUploader,
      ImmutableSet<Account.Id> reviewerAccountIds,
      ImmutableSet<Account.Id> approverAccountIds,
      Path absolutePath) {
    logger.atFine().log("computing path status for %s (bootstrapping mode)", absolutePath);

    AtomicReference<CodeOwnerStatus> codeOwnerStatus =
        new AtomicReference<>(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    if (isProjectOwner(branch.project(), patchSetUploader)) {
      // The uploader of the patch set is a project owner and thus a code owner. This means there
      // is an implicit code owner approval from the patch set uploader so that the path is
      // automatically approved.
      logger.atFine().log(
          "the status for path %s is %s since the patch set uploader is a project owner",
          absolutePath, CodeOwnerStatus.APPROVED.name());
      codeOwnerStatus.set(CodeOwnerStatus.APPROVED);
    } else if (approverAccountIds.stream()
        .anyMatch(approverAccountId -> isProjectOwner(branch.project(), approverAccountId))) {
      // At least one of the approvers is a project owner and thus a code owner.
      logger.atFine().log(
          "the status for path %s is %s since at least one approvers is a project owner",
          absolutePath, CodeOwnerStatus.APPROVED.name());
      codeOwnerStatus.set(CodeOwnerStatus.APPROVED);
    } else if (reviewerAccountIds.stream()
        .anyMatch(reviewerAccountId -> isProjectOwner(branch.project(), reviewerAccountId))) {
      // At least one of the reviewers is a project owner and thus a code owner.
      logger.atFine().log(
          "the status for path %s is %s since at least one reviewer is a project owner",
          absolutePath, CodeOwnerStatus.PENDING.name());
      codeOwnerStatus.set(CodeOwnerStatus.PENDING);
    }

    PathCodeOwnerStatus pathCodeOwnerStatus =
        PathCodeOwnerStatus.create(absolutePath, codeOwnerStatus.get());
    logger.atFine().log("pathCodeOwnerStatus = %s", pathCodeOwnerStatus);
    return pathCodeOwnerStatus;
  }

  /**
   * Gets the code owner status for the given path when the branch contains at least one code owner
   * config file (regular mode).
   */
  private PathCodeOwnerStatus getPathCodeOwnerStatusRegularMode(
      BranchNameKey branch,
      Account.Id patchSetUploader,
      ObjectId revision,
      ImmutableSet<Account.Id> reviewerAccountIds,
      ImmutableSet<Account.Id> approverAccountIds,
      Path absolutePath) {
    logger.atFine().log("computing path status for %s (regular mode)", absolutePath);

    AtomicReference<CodeOwnerStatus> codeOwnerStatus =
        new AtomicReference<>(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    codeOwnerConfigHierarchy.visit(
        branch,
        revision,
        absolutePath,
        codeOwnerConfig -> {
          ImmutableSet<Account.Id> codeOwnerAccountIds =
              getCodeOwnerAccountIds(codeOwnerConfig, absolutePath);
          logger.atFine().log(
              "code owners = %s (code owner config folder path = %s, file name = %s)",
              codeOwnerAccountIds,
              codeOwnerConfig.key().folderPath(),
              codeOwnerConfig.key().fileName().orElse("<default>"));

          if (codeOwnerAccountIds.contains(patchSetUploader)) {
            // If the uploader of the patch set owns the path, there is an implicit code owner
            // approval from the patch set uploader so that the path is automatically approved.
            logger.atFine().log(
                "the status for path %s is %s since the patch set uploader is a code owner",
                absolutePath, CodeOwnerStatus.APPROVED.name());
            codeOwnerStatus.set(CodeOwnerStatus.APPROVED);

            // We can abort since we already found that the path was approved.
            return false;
          }

          if (Collections.disjoint(codeOwnerAccountIds, reviewerAccountIds)) {
            // We need to continue to check if any of the higher-level code owners is a reviewer.
            logger.atFine().log(
                "None of the code owners is a reviewer, continue checking higher-level code owner configs");
            return true;
          }

          logger.atFine().log(
              "One of the code owners is a reviewer, the path status is at least %s",
              CodeOwnerStatus.PENDING.name());

          if (Collections.disjoint(codeOwnerAccountIds, approverAccountIds)) {
            // At least one of the code owners is a reviewer on the change.
            logger.atFine().log(
                "None of the code owners is an approver, continue checking higher-level code owner configs");
            codeOwnerStatus.set(CodeOwnerStatus.PENDING);

            // We need to continue to check if any of the higher-level code owners has approved
            // the change.
            return true;
          }

          // At least one of the code owners approved the change.
          logger.atFine().log(
              "the status for path %s is %s since one of the approvers is a code owner",
              absolutePath, CodeOwnerStatus.APPROVED.name());
          codeOwnerStatus.set(CodeOwnerStatus.APPROVED);

          // We can abort since we already found that the path was approved.
          return false;
        });

    PathCodeOwnerStatus pathCodeOwnerStatus =
        PathCodeOwnerStatus.create(absolutePath, codeOwnerStatus.get());
    logger.atFine().log("pathCodeOwnerStatus = %s", pathCodeOwnerStatus);
    return pathCodeOwnerStatus;
  }

  /** Whether the given account is a project owner of the given project. */
  private boolean isProjectOwner(Project.NameKey project, Account.Id accountId) {
    try {
      boolean isProjectOwner =
          permissionBackend
              .absentUser(accountId)
              .project(project)
              .test(ProjectPermission.WRITE_CONFIG);
      if (isProjectOwner) {
        logger.atFine().log("Account %d is a project owner", accountId.get());
      }
      return isProjectOwner;
    } catch (PermissionBackendException e) {
      throw new StorageException(
          String.format(
              "failed to check owner permission of project %s for account %d",
              project.get(), accountId.get()),
          e);
    }
  }

  /**
   * Gets the IDs of the accounts that own the given path according to the given code owner config.
   *
   * @param codeOwnerConfig the code owner config from which the code owners should be retrieved
   * @param absolutePath the path for which the code owners should be retrieved
   */
  private ImmutableSet<Account.Id> getCodeOwnerAccountIds(
      CodeOwnerConfig codeOwnerConfig, Path absolutePath) {
    return codeOwnerResolver.get().enforceVisibility(false)
        .resolvePathCodeOwners(codeOwnerConfig, absolutePath).stream()
        .map(CodeOwner::accountId)
        .collect(toImmutableSet());
  }

  /**
   * Gets the IDs of the accounts that are reviewer on the given change.
   *
   * @param changeNotes the change notes
   */
  private ImmutableSet<Account.Id> getReviewerAccountIds(ChangeNotes changeNotes) {
    return changeNotes.getReviewers().byState(ReviewerStateInternal.REVIEWER);
  }

  /**
   * Gets the IDs of the accounts that posted a patch set approval on the given revisions that
   * counts as code owner approval.
   *
   * @param requiredApproval approval that is required from code owners to approve the files in a
   *     change
   * @param changeNotes the change notes
   */
  private ImmutableSet<Account.Id> getApproverAccountIds(
      RequiredApproval requiredApproval, ChangeNotes changeNotes) {
    return changeNotes.getApprovals().get(changeNotes.getCurrentPatchSet().id()).stream()
        .filter(requiredApproval::isApprovedBy)
        .map(PatchSetApproval::accountId)
        .collect(toImmutableSet());
  }

  /**
   * Checks whether the given change has an override approval.
   *
   * @param overrideApproval approval that is required to override the code owners submit check.
   * @param changeNotes the change notes
   * @return whether the given change has an override approval
   */
  private boolean hasOverride(RequiredApproval overrideApproval, ChangeNotes changeNotes) {
    return changeNotes.getApprovals().get(changeNotes.getCurrentPatchSet().id()).stream()
        .anyMatch(overrideApproval::isApprovedBy);
  }

  /**
   * Gets the current revision of the destination branch of the given change.
   *
   * <p>This is the revision from which the code owner configs should be read when computing code
   * owners for the files that are touched in the change.
   */
  private ObjectId getDestBranchRevision(Change change) throws IOException {
    try (Repository repository = repoManager.openRepository(change.getProject());
        RevWalk rw = new RevWalk(repository)) {
      Ref ref = repository.exactRef(change.getDest().branch());
      checkNotNull(
          ref,
          "branch %s in repository %s not found",
          change.getDest().branch(),
          change.getProject().get());
      return rw.parseCommit(ref.getObjectId());
    }
  }
}
