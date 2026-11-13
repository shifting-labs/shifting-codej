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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ListFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ReadFile;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;

/** Session-owned built-in capabilities; no provider-specific logic or JSON-tree argument handling. */
public final class BuiltinTools
        implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Set<String> NAMES = Set.of("read_file", "list_files", "search_files", "apply_patch", "shell_command");
    private final WorkspaceFiles files;
    private final SandboxCommand command;
    private final int outputChars;
    private final boolean writable;
    private boolean closed;

    public BuiltinTools(Path worktree, CodingTools limits, boolean writable, List<Path> excluded, ProcessSupervisor supervisor,
            String owner, int outputChars, Consumer<String> prepared)
            throws IOException
    {
        checkArgument(outputChars >= 512, "coding tools require at least 512 output characters");
        this.outputChars = outputChars;
        this.writable = writable;
        files = new WorkspaceFiles(worktree, limits, writable, excluded);
        try {
            command = new SandboxCommand(files, limits, supervisor, owner, prepared);
        }
        catch (IOException | RuntimeException failure) {
            files.close();
            throw failure;
        }
    }

    /** Schema-only tools for validation, without opening files or starting processes. */
    public static List<Tool> definitions(boolean writable) { return tools(null, writable); }

    public List<Tool> tools() { return tools(this, writable); }

    public static boolean contains(String name) { return NAMES.contains(name); }

    private static List<Tool> tools(BuiltinTools owner, boolean writable)
    {
        return List.of(
                Tool.of("read_file", "Read one bounded UTF-8 workspace file and its SHA-256 preimage", ReadFile.class,
                        request -> owner.result(() -> owner.files.read(request))),
                Tool.of("list_files", "List one workspace directory; protected metadata is omitted", ListFiles.class,
                        request -> owner.result(() -> owner.files.list(request))),
                Tool.of("search_files", "Bounded literal text search in UTF-8 workspace files; no regular expressions", SearchFiles.class,
                        request -> owner.result(() -> owner.files.search(request))),
                Tool.of("apply_patch", "Create, update or delete one workspace file; updates/deletes require expectedSha256. Exact replacements must match once", ApplyPatch.class,
                        request -> owner.result(() -> owner.files.patch(request))),
                Tool.of("shell_command", "Run absolute argv in a fixed macOS sandbox: read-only workspace, private scratch, no network, no child processes", ShellCommand.class,
                        request -> owner.result(() -> owner.command.run(request))))
                .stream().filter(tool -> writable || !tool.name().equals("apply_patch")).toList();
    }

    @FunctionalInterface
    private interface Operation
    {
        Record run()
                throws IOException;
    }

    private String result(Operation operation)
    {
        try {
            String result = JSON.writeValueAsString(operation.run());
            if (result.length() > outputChars) {
                throw new CodingToolException("OUTPUT_LIMIT", "Result exceeds the tool output limit; use a smaller file or result limit");
            }
            return result;
        }
        catch (IOException failure) {
            throw new CodingToolException("FILE_IO", "The operation could not access the requested workspace path");
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
