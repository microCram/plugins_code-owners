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

package com.google.gerrit.plugins.codeowners.metrics;

import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.Histogram0;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.metrics.Timer1;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Metrics of the code-owners plugin. */
@Singleton
public class CodeOwnerMetrics {
  // latency metrics
  public final Timer0 computeChangedFilesAgainstAutoMerge;
  public final Timer0 computeChangedFilesAgainstFirstParent;
  public final Timer0 computeFileStatus;
  public final Timer0 computeFileStatuses;
  public final Timer0 computeOwnedPaths;
  public final Timer0 prepareFileStatusComputation;
  public final Timer0 prepareFileStatusComputationForAccount;
  public final Timer0 resolveCodeOwnerConfig;
  public final Timer0 resolveCodeOwnerConfigImport;
  public final Timer0 resolveCodeOwnerConfigImports;
  public final Timer0 resolvePathCodeOwners;
  public final Timer0 runCodeOwnerSubmitRule;

  // code owner config metrics
  public final Histogram0 codeOwnerConfigBackendReadsPerChange;
  public final Histogram0 codeOwnerConfigCacheReadsPerChange;
  public final Timer1<String> loadCodeOwnerConfig;
  public final Timer0 readCodeOwnerConfig;
  public final Timer1<String> parseCodeOwnerConfig;

  // counter metrics
  public final Counter0 countCodeOwnerConfigReads;
  public final Counter0 countCodeOwnerConfigCacheReads;
  public final Counter0 countCodeOwnerSubmitRuleRuns;

  private final MetricMaker metricMaker;

  @Inject
  CodeOwnerMetrics(MetricMaker metricMaker) {
    this.metricMaker = metricMaker;

    // latency metrics
    this.computeChangedFilesAgainstAutoMerge =
        createLatencyTimer(
            "compute_changed_files_against_auto_merge",
            "Latency for computing changed files against auto merge");
    this.computeChangedFilesAgainstFirstParent =
        createLatencyTimer(
            "compute_changed_files_against_first_parent",
            "Latency for computing changed files against first parent");
    this.computeFileStatus =
        createLatencyTimer(
            "compute_file_status", "Latency for computing the file status of one file");
    this.computeFileStatuses =
        createLatencyTimer(
            "compute_file_statuses",
            "Latency for computing file statuses for all files in a change");
    this.computeOwnedPaths =
        createLatencyTimer(
            "compute_owned_paths",
            "Latency for computing the files in a change that are owned by a user");
    this.prepareFileStatusComputation =
        createLatencyTimer(
            "prepare_file_status_computation", "Latency for preparing the file status computation");
    this.prepareFileStatusComputationForAccount =
        createLatencyTimer(
            "compute_file_statuses_for_account",
            "Latency for computing file statuses for an account");
    this.resolveCodeOwnerConfig =
        createLatencyTimer(
            "resolve_code_owner_config", "Latency for resolving a code owner config file");
    this.resolveCodeOwnerConfigImport =
        createLatencyTimer(
            "resolve_code_owner_config_import",
            "Latency for resolving an import of a code owner config file");
    this.resolveCodeOwnerConfigImports =
        createLatencyTimer(
            "resolve_code_owner_config_imports",
            "Latency for resolving all imports of a code owner config file");
    this.resolvePathCodeOwners =
        createLatencyTimer(
            "resolve_path_code_owners", "Latency for resolving the code owners of a path");
    this.runCodeOwnerSubmitRule =
        createLatencyTimer(
            "run_code_owner_submit_rule", "Latency for running the code owner submit rule");

    // code owner config metrics
    this.codeOwnerConfigBackendReadsPerChange =
        createHistogram(
            "code_owner_config_backend_reads_per_change",
            "Number of code owner config backend reads per change");
    this.codeOwnerConfigCacheReadsPerChange =
        createHistogram(
            "code_owner_config_cache_reads_per_change",
            "Number of code owner config cache reads per change");
    this.loadCodeOwnerConfig =
        createTimerWithClassField(
            "load_code_owner_config",
            "Latency for loading a code owner config file (read + parse)",
            "backend");
    this.parseCodeOwnerConfig =
        createTimerWithClassField(
            "parse_code_owner_config", "Latency for parsing a code owner config file", "parser");
    this.readCodeOwnerConfig =
        createLatencyTimer(
            "read_code_owner_config", "Latency for reading a code owner config file");

    // counter metrics
    this.countCodeOwnerConfigReads =
        createCounter(
            "count_code_owner_config_reads",
            "Total number of code owner config reads from backend");
    this.countCodeOwnerConfigCacheReads =
        createCounter(
            "count_code_owner_config_cache_reads",
            "Total number of code owner config reads from cache");
    this.countCodeOwnerSubmitRuleRuns =
        createCounter(
            "count_code_owner_submit_rule_runs", "Total number of code owner submit rule runs");
  }

  private Timer0 createLatencyTimer(String name, String description) {
    return metricMaker.newTimer(
        "code_owners/" + name,
        new Description(description).setCumulative().setUnit(Units.MILLISECONDS));
  }

  private Timer1<String> createTimerWithClassField(
      String name, String description, String fieldName) {
    Field<String> CODE_OWNER_BACKEND_FIELD =
        Field.ofString(
                fieldName, (metadataBuilder, fieldValue) -> metadataBuilder.className(fieldValue))
            .build();

    return metricMaker.newTimer(
        "code_owners/" + name,
        new Description(description).setCumulative().setUnit(Description.Units.MILLISECONDS),
        CODE_OWNER_BACKEND_FIELD);
  }

  private Counter0 createCounter(String name, String description) {
    return metricMaker.newCounter("code_owners/" + name, new Description(description).setRate());
  }

  private Histogram0 createHistogram(String name, String description) {
    return metricMaker.newHistogram(
        "code_owners/" + name, new Description(description).setCumulative());
  }
}
