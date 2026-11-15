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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolDefinition;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ListFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ReadFile;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;
import static java.util.Objects.requireNonNull;

/** Compatibility facade and sole owner of the shared workspace/command resources. Handlers borrow narrow capabilities. */
public final class BuiltinTools
        implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final List<ToolDefinition<?, ?>> DEFINITIONS = List.of(ReadFileTool.DEFINITION, ListFilesTool.DEFINITION,
            SearchFilesTool.DEFINITION, ApplyPatchTool.DEFINITION, ShellCommandTool.DEFINITION);
    private static final Set<String> NAMES = DEFINITIONS.stream().map(definition -> definition.descriptor().name()).collect(Collectors.toUnmodifiableSet());
    private final WorkspaceFiles files;
    private final SandboxCommand command;
    private final int outputChars;
    private final ToolRequirements authority;
    private final List<AgentTool<?, ?>> handlers;
    private final List<Tool> legacy;
    private boolean closed;

    public BuiltinTools(Path worktree, CodingTools limits, boolean writable, List<Path> excluded, ProcessSupervisor supervisor,
            String owner, int outputChars, Consumer<String> prepared)
            throws IOException
    {
        checkArgument(outputChars >= 512, "coding tools require at least 512 output characters");
        this.outputChars = outputChars;
        var grants = ToolRequirements.builder().read(Path.of(".")).require(ToolCapability.PROCESS_EXECUTE);
        if (writable) {
            grants.write(Path.of("."));
        }
        authority = grants.build();
        files = new WorkspaceFiles(worktree, limits, writable, excluded);
        try {
            command = new SandboxCommand(files, limits, supervisor, owner, prepared);
        }
        catch (IOException | RuntimeException failure) {
            files.close();
            throw failure;
        }
        var read = new ReadFileTool(files);
        var list = new ListFilesTool(files);
        var search = new SearchFilesTool(files);
        var shell = new ShellCommandTool(command);
        List<AgentTool<?, ?>> installed = new ArrayList<>(List.of(read, list, search));
        List<Tool> facades = new ArrayList<>(List.of(legacy(ReadFile.class, read), legacy(ListFiles.class, list), legacy(SearchFiles.class, search)));
        if (writable) {
            var patch = new ApplyPatchTool(files);
            installed.add(patch);
            facades.add(legacy(ApplyPatch.class, patch));
        }
        installed.add(shell);
        facades.add(legacy(ShellCommand.class, shell));
        handlers = List.copyOf(installed);
        legacy = List.copyOf(facades);
    }

    /** Schema-only tools for validation, without opening files or starting processes. */
    public static List<Tool> definitions(boolean writable)
    {
        return DEFINITIONS.stream().filter(definition -> writable || definition != ApplyPatchTool.DEFINITION)
                .map(definition -> Tool.withSchema(definition.descriptor().name(), definition.descriptor().description(), definition.parameters().schema().json(),
                        arguments -> {
                            throw new UnsupportedOperationException("Schema-only tool has no execution resources");
                        })).toList();
    }

    public List<Tool> tools() { return legacy; }

    /** Immutable typed handlers borrowing this facade's resources. The caller must keep this owner open and serialize invocations. */
    public List<AgentTool<?, ?>> agentTools() { return handlers; }

    public static boolean contains(String name) { return NAMES.contains(name); }

    private <P extends Record, R> Tool legacy(Class<P> parameters, AgentTool<P, R> handler)
    {
        var definition = handler.definition();
        // Keep the established record binder inside Tool.call, after approval, including its direct-call coercion behavior.
        return Tool.of(definition.descriptor().name(), definition.descriptor().description(), parameters, request -> invoke(handler, request));
    }

    private <P, R> String invoke(AgentTool<P, R> handler, P request)
            throws Exception
    {
        ToolRequirements requested = handler.requirements(request, new LegacyContext(authority));
        if (!authority.permits(requested)) {
            throw new SecurityException("Coding tool requirements are not granted");
        }
        ToolOutcome<R> outcome = handler.execute(request, new LegacyContext(requested)).completion().toCompletableFuture().get();
        if (outcome.state() != ToolOutcome.State.COMPLETED) {
            var failure = outcome.failure().orElseThrow();
            throw new CodingToolException(failure.code(), failure.message(), outcome.state() == ToolOutcome.State.UNCERTAIN);
        }
        String result = handler.definition().results().encode(outcome.value().orElseThrow()).text();
        if (result.length() > outputChars) {
            throw new CodingToolException("OUTPUT_LIMIT", "Result exceeds the tool output limit; use a smaller file or result limit");
        }
        return result;
    }

    private record LegacyContext(ToolRequirements grants)
            implements ToolExecutionContext
    {
        LegacyContext { requireNonNull(grants, "grants is null"); }

        @Override
        public String invocationId() { return "legacy-built-in"; }

        @Override
        public Optional<Instant> deadline() { return Optional.empty(); }

        @Override
        public boolean cancellationRequested() { return false; }

        @Override
        public void recordPrepared(ToolResource resource)
        {
            // The legacy constructor's prepared callback already checkpoints each native launch before effects.
            requireNonNull(resource, "resource is null");
        }
    }

    public static String failure(String code, String message)
    {
        try {
            return JSON.writeValueAsString(new ToolMessages.Failure(code, message));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot encode tool failure", impossible);
        }
    }

    /** Runtime sandbox check; the caller checkpoints its intent before enabling this session. */
    public void initialize()
            throws IOException
    {
        command.check();
    }

    /** Verifies session-owned command work without acknowledging an uncommitted result. */
    public ToolStopResult stopAndVerify() { return command.stopAndVerify(); }

    /** Called only after the engine has durably committed the completed tool outcome. */
    public void committed() { command.committed(); }

    @Override
    public void close()
            throws IOException
    {
        if (!closed) {
            command.close();
            files.close();
            closed = true;
        }
    }
}
