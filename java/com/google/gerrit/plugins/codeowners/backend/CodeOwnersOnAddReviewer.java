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

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.events.ReviewerAddedListener;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.plugins.codeowners.backend.config.CodeOwnersPluginConfiguration;
import com.google.gerrit.plugins.codeowners.backend.config.CodeOwnersPluginProjectConfigSnapshot;
import com.google.gerrit.plugins.codeowners.metrics.CodeOwnerMetrics;
import com.google.gerrit.plugins.codeowners.util.JgitPath;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.util.AccountTemplateUtil;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Callback that is invoked when a user is added as a reviewer.
 *
 * <p>If a code owner was added as reviewer add a change message that lists the files that are owned
 * by the reviewer.
 */
@Singleton
public class CodeOwnersOnAddReviewer implements ReviewerAddedListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String TAG_ADD_REVIEWER =
      ChangeMessagesUtil.AUTOGENERATED_BY_GERRIT_TAG_PREFIX + "code-owners:addReviewer";

  private final WorkQueue workQueue;
  private final OneOffRequestContext oneOffRequestContext;
  private final CodeOwnersPluginConfiguration codeOwnersPluginConfiguration;
  private final CodeOwnerApprovalCheck codeOwnerApprovalCheck;
  private final Provider<CurrentUser> userProvider;
  private final RetryHelper retryHelper;
  private final ChangeNotes.Factory changeNotesFactory;
  private final ChangeMessagesUtil changeMessageUtil;
  private final CodeOwnerMetrics codeOwnerMetrics;

  @Inject
  CodeOwnersOnAddReviewer(
      WorkQueue workQueue,
      OneOffRequestContext oneOffRequestContext,
      CodeOwnersPluginConfiguration codeOwnersPluginConfiguration,
      CodeOwnerApprovalCheck codeOwnerApprovalCheck,
      Provider<CurrentUser> userProvider,
      RetryHelper retryHelper,
      ChangeNotes.Factory changeNotesFactory,
      ChangeMessagesUtil changeMessageUtil,
      CodeOwnerMetrics codeOwnerMetrics) {
    this.workQueue = workQueue;
    this.oneOffRequestContext = oneOffRequestContext;
    this.codeOwnersPluginConfiguration = codeOwnersPluginConfiguration;
    this.codeOwnerApprovalCheck = codeOwnerApprovalCheck;
    this.userProvider = userProvider;
    this.retryHelper = retryHelper;
    this.changeNotesFactory = changeNotesFactory;
    this.changeMessageUtil = changeMessageUtil;
    this.codeOwnerMetrics = codeOwnerMetrics;
  }

  @Override
  public void onReviewersAdded(Event event) {
    Change.Id changeId = Change.id(event.getChange()._number);
    Project.NameKey projectName = Project.nameKey(event.getChange().project);

    CodeOwnersPluginProjectConfigSnapshot codeOwnersConfig =
        codeOwnersPluginConfiguration.getProjectConfig(projectName);
    int maxPathsInChangeMessages = codeOwnersConfig.getMaxPathsInChangeMessages();
    if (codeOwnersConfig.isDisabled(event.getChange().branch) || maxPathsInChangeMessages <= 0) {
      return;
    }

    CurrentUser user = userProvider.get();
    if (codeOwnersConfig.enableAsyncMessageOnAddReviewer()) {
      // post change message asynchronously to avoid adding latency to PostReviewers and PostReview
      logger.atFine().log("schedule asynchronous posting of the change message");
      @SuppressWarnings("unused")
      WorkQueue.Task<?> possiblyIgnoredError =
          (WorkQueue.Task<?>)
              workQueue
                  .getDefaultQueue()
                  .submit(
                      () -> {
                        try (ManualRequestContext ignored =
                            oneOffRequestContext.openAs(user.getAccountId())) {
                          postChangeMessage(
                              user,
                              projectName,
                              changeId,
                              event.getReviewers(),
                              event.getWhen(),
                              maxPathsInChangeMessages,
                              /* asynchronous= */ true);
                        }
                      });
    } else {
      logger.atFine().log("post change message synchronously");
      postChangeMessage(
          user,
          projectName,
          changeId,
          event.getReviewers(),
          event.getWhen(),
          maxPathsInChangeMessages,
          /* asynchronous= */ false);
    }
  }

  private void postChangeMessage(
      CurrentUser currentUser,
      Project.NameKey projectName,
      Change.Id changeId,
      List<AccountInfo> reviewers,
      Instant when,
      int maxPathsInChangeMessages,
      boolean asynchronous) {
    try (Timer1.Context<String> ctx =
        codeOwnerMetrics.addChangeMessageOnAddReviewer.start(
            asynchronous ? "asynchronous" : "synchronous")) {
      retryHelper
          .changeUpdate(
              "addCodeOwnersMessageOnAddReviewer",
              updateFactory -> {
                try (BatchUpdate batchUpdate =
                    updateFactory.create(projectName, currentUser, when)) {
                  batchUpdate.addOp(changeId, new Op(reviewers, maxPathsInChangeMessages));
                  batchUpdate.execute();
                }
                return null;
              })
          .call();
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Failed to post code-owners change message for reviewer on change %s in project %s.",
          changeId, projectName);
    }
  }

  private class Op implements BatchUpdateOp {
    private final List<AccountInfo> reviewers;
    private final int limit;

    Op(List<AccountInfo> reviewers, int limit) {
      this.reviewers = reviewers;
      this.limit = limit;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws Exception {
      String message =
          reviewers.stream()
              .map(accountInfo -> Account.id(accountInfo._accountId))
              .map(
                  reviewerAccountId ->
                      buildMessageForReviewer(
                          ctx.getProject(), ctx.getChange().getId(), reviewerAccountId))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .collect(joining("\n"));

      if (message.isEmpty()) {
        return false;
      }

      changeMessageUtil.setChangeMessage(ctx, message, TAG_ADD_REVIEWER);
      return true;
    }

    private Optional<String> buildMessageForReviewer(
        Project.NameKey projectName, Change.Id changeId, Account.Id reviewerAccountId) {
      ChangeNotes changeNotes = changeNotesFactory.create(projectName, changeId);

      ImmutableList<Path> ownedPaths;
      try {
        // limit + 1, so that we can show an indicator if there are more than <limit> files.
        ownedPaths =
            OwnedChangedFile.getOwnedPaths(
                codeOwnerApprovalCheck.getOwnedPaths(
                    changeNotes,
                    changeNotes.getCurrentPatchSet(),
                    reviewerAccountId,
                    /* start= */ 0,
                    limit + 1));
      } catch (RestApiException e) {
        logger.atFine().withCause(e).log(
            "Couldn't compute owned paths of change %s for account %s",
            changeNotes.getChangeId(), reviewerAccountId.get());
        return Optional.empty();
      }

      if (ownedPaths.isEmpty()) {
        // this reviewer doesn't own any of the modified paths
        return Optional.empty();
      }

      StringBuilder message = new StringBuilder();
      message.append(
          String.format(
              "%s, who was added as reviewer owns the following files:\n",
              AccountTemplateUtil.getAccountTemplate(reviewerAccountId)));

      if (ownedPaths.size() <= limit) {
        appendPaths(message, ownedPaths.stream());
      } else {
        appendPaths(message, ownedPaths.stream().limit(limit));
        message.append("(more files)\n");
      }

      return Optional.of(message.toString());
    }

    private void appendPaths(StringBuilder message, Stream<Path> pathsToAppend) {
      pathsToAppend.forEach(
          path -> message.append(String.format("* %s\n", JgitPath.of(path).get())));
    }
  }
}
