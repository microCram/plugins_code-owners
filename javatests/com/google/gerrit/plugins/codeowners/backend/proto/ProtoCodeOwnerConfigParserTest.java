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

package com.google.gerrit.plugins.codeowners.backend.proto;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerConfigSubject.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.util.Objects.requireNonNull;

import com.google.gerrit.entities.Project;
import com.google.gerrit.plugins.codeowners.backend.AbstractCodeOwnerConfigParserTest;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigParser;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerReference;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerSet;
import com.google.protobuf.TextFormat;
import java.util.Arrays;
import org.junit.Test;

/** Tests for {@link ProtoCodeOwnerConfigParser}. */
public class ProtoCodeOwnerConfigParserTest extends AbstractCodeOwnerConfigParserTest {
  @Override
  protected Class<? extends CodeOwnerConfigParser> getCodeOwnerConfigParserClass() {
    return ProtoCodeOwnerConfigParser.class;
  }

  @Override
  protected String getCodeOwnerConfig(CodeOwnerConfig codeOwnerConfig) {
    StringBuilder b = new StringBuilder();
    b.append("owners_config {\n");
    if (codeOwnerConfig.ignoreParentCodeOwners()) {
      b.append("  ignore_parent_owners: true\n");
    }
    for (CodeOwnerSet codeOwnerSet : codeOwnerConfig.codeOwnerSets()) {
      if (!codeOwnerSet.codeOwners().isEmpty()) {
        b.append("  owner_sets {\n");
        for (String pathExpression : codeOwnerSet.pathExpressions()) {
          b.append(String.format("    path_expressions: \"%s\"\n", pathExpression));
        }
        for (CodeOwnerReference codeOwnerReference : codeOwnerSet.codeOwners()) {
          b.append(
              String.format(
                  "    owners {\n      email: \"%s\"\n    }\n", codeOwnerReference.email()));
        }
        b.append("  }\n");
      }
    }
    b.append("}\n");
    return b.toString();
  }

  @Test
  public void codeOwnerConfigWithInvalidEmails() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfig("@example.com", "admin@", EMAIL_1, "admin@example@com", EMAIL_2),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly("@example.com", "admin@", EMAIL_1, "admin@example@com", EMAIL_2),
        getCodeOwnerConfig("@example.com", "admin@", EMAIL_1, "admin@example@com", EMAIL_2));
  }

  @Test
  public void cannotParseCodeOwnerConfigWithInvalidLines() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey =
        CodeOwnerConfig.Key.create(Project.nameKey("project"), "master", "/");
    assertThrows(
        TextFormat.ParseException.class,
        () ->
            codeOwnerConfigParser.parse(
                codeOwnerConfigKey, "owners_config {\n  owner_sets {\nINVALID_LINE\n  }\n}\n"));
  }

  @Test
  public void codeOwnerConfigWithInlineComments() throws Exception {
    assertParseAndFormat(
        String.format(
            "owners_config {\n  owner_sets {\n    owners {\n      email: \"%s\" # comment\n    }\n  }\n}\n",
            EMAIL_1),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1),
        getCodeOwnerConfig(EMAIL_1));
  }

  @Test
  public void codeOwnerConfigWithNonSortedEmails() throws Exception {
    assertParseAndFormat(
        getCodeOwnerConfigWithGivenEmailOrder(EMAIL_3, EMAIL_2, EMAIL_1),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig)
                .hasCodeOwnerSetsThat()
                .onlyElement()
                .hasCodeOwnersEmailsThat()
                .containsExactly(EMAIL_1, EMAIL_2, EMAIL_3),
        getCodeOwnerConfig(EMAIL_1, EMAIL_2, EMAIL_3));
  }

  @Test
  public void setMultipleCodeOwnerSetsWithoutPathExpressions() throws Exception {
    CodeOwnerSet codeOwnerSet1 = CodeOwnerSet.createWithoutPathExpressions(EMAIL_1, EMAIL_3);
    CodeOwnerSet codeOwnerSet2 = CodeOwnerSet.createWithoutPathExpressions(EMAIL_2);
    assertParseAndFormat(
        getCodeOwnerConfig(false, codeOwnerSet1, codeOwnerSet2),
        codeOwnerConfig ->
            assertThat(codeOwnerConfig.codeOwnerSets())
                .containsExactly(codeOwnerSet1, codeOwnerSet2)
                .inOrder(),
        getCodeOwnerConfig(false, codeOwnerSet1, codeOwnerSet2));
  }

  /**
   * Gets a code owner config that contains the given emails as code owners in the order in which
   * the emails are provided.
   *
   * @param firstEmail the email of the first code owner
   * @param moreEmails the emails of further code owners
   */
  private String getCodeOwnerConfigWithGivenEmailOrder(String firstEmail, String... moreEmails) {
    requireNonNull(firstEmail, "firstEmail");
    StringBuilder b =
        new StringBuilder()
            .append("owners_config {\n")
            .append("  owner_sets {\n")
            .append(String.format("    owners {\n      email: \"%s\"\n    }\n", firstEmail));
    Arrays.stream(moreEmails)
        .forEach(
            email -> b.append(String.format("    owners {\n      email: \"%s\"\n    }\n", email)));
    b.append("  }\n").append("}\n");
    return b.toString();
  }

  @Test
  public void cannotFormatCodeOwnerSetThatIgnoresGlobalAndParentCodeOwners() throws Exception {
    CodeOwnerSet codeOwnerSet =
        CodeOwnerSet.builder()
            .setIgnoreGlobalAndParentCodeOwners()
            .addPathExpression("*.md")
            .addPathExpression("foo")
            .addCodeOwnerEmail(EMAIL_1)
            .addCodeOwnerEmail(EMAIL_2)
            .build();

    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"))
            .addCodeOwnerSet(codeOwnerSet)
            .build();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> codeOwnerConfigParser.formatAsString(codeOwnerConfig));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("ignoreGlobaleAndParentCodeOwners is not supported");
  }
}
