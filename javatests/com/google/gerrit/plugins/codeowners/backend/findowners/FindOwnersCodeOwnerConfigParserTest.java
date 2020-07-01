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

package com.google.gerrit.plugins.codeowners.backend.findowners;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerConfigSubject.assertThat;

import com.google.gerrit.entities.Project;
import com.google.gerrit.plugins.codeowners.backend.AbstractCodeOwnerConfigParserTest;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigParser;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerSet;
import org.junit.Test;

/** Tests for {@link FindOwnersCodeOwnerConfigParser}. */
public class FindOwnersCodeOwnerConfigParserTest extends AbstractCodeOwnerConfigParserTest {
  @Override
  protected Class<? extends CodeOwnerConfigParser> getCodeOwnerConfigParserClass() {
    return FindOwnersCodeOwnerConfigParser.class;
  }

  @Override
  protected String getCodeOwnerConfig(boolean ignoreParentCodeOwners, String... emails) {
    StringBuilder b = new StringBuilder();
    if (ignoreParentCodeOwners) {
      b.append("set noparent\n");
    }
    b.append(String.join("\n", emails));
    if (emails.length > 0) {
      b.append('\n');
    }
    return b.toString();
  }

  @Test
  public void codeOwnerConfigWithInvalidEmails_invalidEmailsAreIgnored() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfig(EMAIL_1, "@example.com", "admin@", "admin@example@com", EMAIL_2),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1, EMAIL_2),
        getCodeOwnerConfig(EMAIL_1, EMAIL_2));
  }

  @Test
  public void codeOwnerConfigWithInvalidLines_invalidLinesAreIgnored() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfig(EMAIL_1, "INVALID", "NOT_AN_EMAIL", EMAIL_2),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1, EMAIL_2),
        getCodeOwnerConfig(EMAIL_1, EMAIL_2));
  }

  @Test
  public void codeOwnerConfigWithInlineComments() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfig(EMAIL_1, EMAIL_2 + " # some comment", EMAIL_3),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1, EMAIL_2, EMAIL_3),
        getCodeOwnerConfig(EMAIL_1, EMAIL_2, EMAIL_3));
  }

  @Test
  public void codeOwnerConfigWithNonSortedEmails() throws Exception {
    assertParseAndFormat(
        String.join("\n", EMAIL_3, EMAIL_2, EMAIL_1) + "\n",
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1, EMAIL_2, EMAIL_3),
        getCodeOwnerConfig(EMAIL_1, EMAIL_2, EMAIL_3));
  }

  @Test
  public void setNoParentCanBeSetMultipleTimes() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfig(true, EMAIL_1) + "\nset noparent\nset noparent",
        codeOwnerConfig -> {
          assertThat(codeOwnerConfig).hasIgnoreParentCodeOwnersThat().isTrue();
          assertThat(codeOwnerConfig)
              .hasCodeOwnerSetsThat()
              .onlyElement()
              .hasCodeOwnersEmailsThat()
              .containsExactly(EMAIL_1);
        },
        getCodeOwnerConfig(true, EMAIL_1));
  }

  @Test
  public void formatCodeOwnerConfigWithMultipleCodeOwnerSets() throws Exception {
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(
                CodeOwnerConfig.Key.create(Project.nameKey("project"), "master", "/"))
            .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(EMAIL_1, EMAIL_3))
            .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(EMAIL_2))
            .build();
    assertThat(codeOwnerConfigParser.formatAsString(codeOwnerConfig))
        .isEqualTo(getCodeOwnerConfig(EMAIL_1, EMAIL_2, EMAIL_3));
  }
}
