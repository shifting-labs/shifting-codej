/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.shiftinglabs.codej.cli.api.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBuiltinTools
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    record Definition(String name, String description, String schema) {}

    @Test
    void definitionsExactlyMatchThePreviouslyVerifiedV1Artifact()
            throws Exception
    {
        List<Definition> expected;
        try (var input = TestBuiltinTools.class.getResourceAsStream("builtin-definitions-v1.json")) {
            expected = JSON.readerForListOf(Definition.class).readValue(input);
        }
        assertThat(BuiltinTools.definitions(true).stream().map(tool -> new Definition(tool.name(), tool.description(), tool.inputSchema())).toList()).isEqualTo(expected);
        assertThat(BuiltinTools.definitions(false).stream().map(Tool::name).toList()).containsExactly("read_file", "list_files", "search_files", "shell_command");
        expected.forEach(definition -> assertThat(BuiltinTools.contains(definition.name())).isTrue());
        assertThat(BuiltinTools.contains("other")).isFalse();
        assertThat(BuiltinTools.failure("DENIED", "blocked")).isEqualTo("{\"code\":\"DENIED\",\"message\":\"blocked\"}");
    }

    private BuiltinTools owner(boolean writable, int outputChars, FakeCodingSupervisor supervisor)
            throws Exception
    {
        return new BuiltinTools(directory, CodingTools.defaults(), writable, List.of(directory.resolve("private")), supervisor,
                "facade-test", outputChars, launch -> {});
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void typedHandlersAndTheLegacyFacadeUseTheSameResourcesAndWireContracts()
            throws Exception
    {
        Files.writeString(directory.resolve("note"), "first\nsecond\nthird");
        var supervisor = new FakeCodingSupervisor();
        try (BuiltinTools owner = owner(true, 65536, supervisor)) {
            assertThat(supervisor.specs).isEmpty();
            assertThat(owner.agentTools()).hasSize(5);
            assertThat(owner.agentTools().stream().map(tool -> new Definition(tool.definition().descriptor().name(), tool.definition().descriptor().description(),
                    tool.definition().parameters().schema().json())).toList())
                    .isEqualTo(owner.tools().stream().map(tool -> new Definition(tool.name(), tool.description(), tool.inputSchema())).toList());
            assertThatThrownBy(() -> owner.agentTools().clear()).isInstanceOf(UnsupportedOperationException.class);
            String legacy = owner.tools().getFirst().call("{\"path\":\"note\",\"startLine\":\"2\",\"maximumCharacters\":\"6\"}");
            var excerpt = JSON.readValue(legacy, ToolMessages.FileContent.class);
            assertThat(excerpt.text()).isEqualTo("second");
            String typed = invoke(owner.agentTools().getFirst(), "{\"path\":\"note\"}", AbstractTestWorkspaceReader.readContext("note"));
            assertThat(JSON.readValue(typed, ToolMessages.FileContent.class).text()).isEqualTo("first\nsecond\nthird");
        }
        assertThat(supervisor.closed).isFalse();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void readOnlyInstallationWithholdsEditorHandlersAndLegacyTools()
            throws Exception
    {
        try (BuiltinTools owner = owner(false, 65536, new FakeCodingSupervisor())) {
            assertThat(owner.tools()).extracting(Tool::name).doesNotContain("apply_patch");
            assertThat(owner.agentTools()).noneMatch(tool -> tool instanceof ApplyPatchTool);
            assertThat(owner.agentTools()).hasSize(4);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void legacyOutputLimitsRemainConfigurableBeyondTheDefaultSpiEnvelope()
            throws Exception
    {
        Files.writeString(directory.resolve("note"), "x".repeat(1000));
        try (BuiltinTools owner = owner(false, 512, new FakeCodingSupervisor())) {
            assertThatThrownBy(() -> owner.tools().getFirst().call("{\"path\":\"note\"}"))
                    .isInstanceOf(CodingToolException.class).extracting(failure -> ((CodingToolException) failure).failure().code()).isEqualTo("OUTPUT_LIMIT");
        }
        String value = "\u0001".repeat(14000);
        Files.writeString(directory.resolve("note"), value);
        try (BuiltinTools owner = owner(false, 100000, new FakeCodingSupervisor())) {
            String result = owner.tools().getFirst().call("{\"path\":\"note\",\"maximumCharacters\":16384}");
            assertThat(result.length()).isGreaterThan(65536);
            assertThat(JSON.readValue(result, ToolMessages.FileContent.class).text()).isEqualTo(value);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void legacyCommandBridgeRetainsDurableAcknowledgementAndBorrowedServices()
            throws Exception
    {
        var supervisor = new FakeCodingSupervisor();
        try (BuiltinTools owner = owner(false, 65536, supervisor)) {
            owner.initialize();
            assertThat(supervisor.acknowledged).isEmpty();
            owner.committed();
            assertThat(supervisor.acknowledged).containsExactly("launch-1");
            String result = owner.tools().getLast().call("{\"argv\":[\"/usr/bin/true\"],\"directory\":\".\",\"timeoutSeconds\":1}");
            assertThat(JSON.readValue(result, ToolMessages.CommandResult.class).exitCode()).isZero();
            assertThat(owner.stopAndVerify().stopped()).isTrue();
            assertThat(supervisor.acknowledged).containsExactly("launch-1");
            owner.committed();
            assertThat(supervisor.acknowledged).containsExactly("launch-1", "launch-2");
        }
        assertThat(supervisor.closed).isFalse();
    }

    private static <P, R> String invoke(AgentTool<P, R> handler, String json, ToolExecutionContext context)
            throws Exception
    {
        P parameters = handler.definition().parameters().decode(ToolArguments.of(json));
        var execution = handler.execute(parameters, context);
        var outcome = execution.completion().toCompletableFuture().get();
        assertThat(outcome.state()).isEqualTo(ToolOutcome.State.COMPLETED);
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
        return handler.definition().results().encode(outcome.value().orElseThrow()).text();
    }
}
