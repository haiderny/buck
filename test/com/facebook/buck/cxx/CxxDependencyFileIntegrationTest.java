/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CxxDependencyFileIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private BuildTarget target;
  private BuildTarget preprocessTarget;

  @Parameterized.Parameters(name = "sandbox_sources={0},buckd={1}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        ImmutableList.of(false, true), ImmutableList.of(false, true));
  }

  @Parameterized.Parameter(value = 0)
  public boolean sandboxSource;

  @Parameterized.Parameter(value = 1)
  public boolean buckd;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "depfiles", tmp);
    workspace.setUp();
    String sandboxSourcesConfig = "  sandbox_sources = " + sandboxSource + "\n";
    String config =
        "[cxx]\n"
            + sandboxSourcesConfig
            + "  cppflags = -Wall -Werror\n"
            + "  cxxppflags = -Wall -Werror\n"
            + "  cflags = -Wall -Werror\n"
            + "  cxxflags = -Wall -Werror\n";
    workspace.writeContentsToPath(config, ".buckconfig");

    // Run a build and make sure it's successful.
    runCommand("build", "//:test").assertSuccess();

    // Find the target used for preprocessing and verify it ran.
    target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:test");
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform);
    String source = "test.cpp";
    preprocessTarget = cxxSourceRuleFactory.createCompileBuildTarget(source);
    workspace.getBuildLog().assertTargetBuiltLocally(preprocessTarget.toString());
  }

  @Test
  public void modifyingUsedHeaderCausesRebuild() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "used.h");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void modifyingUnusedHeaderDoesNotCauseRebuild() throws IOException {
    workspace.writeContentsToPath("#define SOMETHING", "unused.h");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY)));
  }

  @Test
  public void modifyingOriginalSourceCausesRebuild() throws IOException {
    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 1; }", "test.cpp");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("   int main() { return 1; }", "test.cpp");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));

    workspace.resetBuildLogFile();
    workspace.writeContentsToPath("int main() { return 2; }", "test.cpp");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  @Test
  public void removingUsedHeaderAndReferenceToItCausesRebuild() throws IOException {
    workspace.writeContentsToPath("int main() { return 1; }", "test.cpp");
    Files.delete(workspace.getPath("used.h"));
    workspace.replaceFileContents("BUCK", "\'used.h\',", "");
    runCommand("build", target.toString()).assertSuccess();
    assertThat(
        workspace.getBuildLog().getLogEntry(preprocessTarget).getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.BUILT_LOCALLY)));
  }

  private ProjectWorkspace.ProcessResult runCommand(String... args) throws IOException {
    if (buckd) {
      return workspace.runBuckdCommand(args);
    } else {
      return workspace.runBuckCommand(args);
    }
  }
}
