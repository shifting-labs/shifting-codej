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
package io.github.shiftinglabs.codej.tool.consumer;

import io.github.shiftinglabs.codej.cli.api.tools.ApplyPatchTool;
import io.github.shiftinglabs.codej.cli.api.tools.CommandExecutor;
import io.github.shiftinglabs.codej.cli.api.tools.ListFilesTool;
import io.github.shiftinglabs.codej.cli.api.tools.ReadFileTool;
import io.github.shiftinglabs.codej.cli.api.tools.SearchFilesTool;
import io.github.shiftinglabs.codej.cli.api.tools.ShellCommandTool;
import io.github.shiftinglabs.codej.cli.api.tools.ToolMessages;
import io.github.shiftinglabs.codej.cli.api.tools.WorkspaceEditor;
import io.github.shiftinglabs.codej.cli.api.tools.WorkspaceReader;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Upstream usage: the same production handlers operate against alternate resources, without engine/provider changes. */
class TestBuiltinHandlers
{
    private static final String HASH = "a".repeat(64);
    private static final ToolMessages.FileContent CONTENT = new ToolMessages.FileContent("note", HASH, "hello", 1, false);
    private static final ToolMessages.FileListing LISTING = new ToolMessages.FileListing(List.of(new ToolMessages.FileEntry("note", ToolMessages.FileKind.FILE)), false);
    private static final ToolMessages.SearchResult SEARCH = new ToolMessages.SearchResult(List.of(new ToolMessages.SearchMatch("note", 1, "hello")), false, 5);
    private static final ToolMessages.PatchResult PATCH = new ToolMessages.PatchResult("note", ToolMessages.PatchAction.CREATE, Optional.of(HASH));
    private static final ToolMessages.CommandResult COMMAND = new ToolMessages.CommandResult(0, ProcessExit.Reason.EXITED, "", "", false, false);
    private static final ToolFailure IO_FAILURE = new ToolFailure("FILE_IO", "The operation could not access the requested workspace path");

    private static final class Resources
            implements WorkspaceReader, WorkspaceEditor, CommandExecutor
    {
        Object expectedRequest;
        int calls;
        boolean failing;

        private void called(Object request)
                throws IOException
        {
            assertThat(request).isEqualTo(expectedRequest);
            calls++;
            if (failing) {
                throw new IOException("private backend diagnostic");
            }
        }

        @Override
        public ToolMessages.FileContent read(ToolMessages.ReadFile request, ToolExecutionContext context)
                throws IOException
        {
            called(request);
            return CONTENT;
        }

        @Override
        public ToolMessages.FileListing list(ToolMessages.ListFiles request, ToolExecutionContext context)
                throws IOException
        {
            called(request);
            return LISTING;
        }

        @Override
        public ToolMessages.SearchResult search(ToolMessages.SearchFiles request, ToolExecutionContext context)
                throws IOException
        {
            called(request);
            return SEARCH;
        }

        @Override
        public ToolMessages.PatchResult apply(ToolMessages.ApplyPatch request, ToolExecutionContext context)
                throws IOException
        {
            called(request);
            return PATCH;
        }

        @Override
        public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
        {
            context.recordPrepared(new ToolResource("process.launch", "fake-launch"));
            try {
                called(request);
                return ToolExecutions.completed(COMMAND);
            }
            catch (IOException failure) {
                return ToolExecutions.finished(ToolOutcome.failed(IO_FAILURE));
            }
        }
    }

    abstract static class Binding<P, R>
            extends AbstractTestAgentTool<P, R>
    {
        final Resources resources = new Resources();
        private AgentTool<P, R> tool;

        protected abstract AgentTool<P, R> bind(Resources resources);

        @BeforeEach
        void setup()
        {
            resources.expectedRequest = input();
            tool = bind(resources);
        }

        @Override
        protected AgentTool<P, R> tool() { return tool; }

        @Override
        protected int calls() { return resources.calls; }

        @Override
        protected void failBackend() { resources.failing = true; }

        @Override
        protected ToolFailure expectedFailure() { return IO_FAILURE; }
    }

    @Nested
    class Read
            extends Binding<ToolMessages.ReadFile, ToolMessages.FileContent>
    {
        @Override
        protected AgentTool<ToolMessages.ReadFile, ToolMessages.FileContent> bind(Resources resources) { return new ReadFileTool(resources); }

        @Override
        protected ToolMessages.ReadFile input() { return new ToolMessages.ReadFile("note"); }

        @Override
        protected ToolMessages.FileContent expected() { return CONTENT; }

        @Override
        protected ToolRequirements requirements() { return ToolRequirements.builder().read(Path.of("note")).build(); }
    }

    @Nested
    class ListDirectory
            extends Binding<ToolMessages.ListFiles, ToolMessages.FileListing>
    {
        @Override
        protected AgentTool<ToolMessages.ListFiles, ToolMessages.FileListing> bind(Resources resources) { return new ListFilesTool(resources); }

        @Override
        protected ToolMessages.ListFiles input() { return new ToolMessages.ListFiles(".", 10); }

        @Override
        protected ToolMessages.FileListing expected() { return LISTING; }

        @Override
        protected ToolRequirements requirements() { return ToolRequirements.builder().read(Path.of(".")).build(); }
    }

    @Nested
    class Search
            extends Binding<ToolMessages.SearchFiles, ToolMessages.SearchResult>
    {
        @Override
        protected AgentTool<ToolMessages.SearchFiles, ToolMessages.SearchResult> bind(Resources resources) { return new SearchFilesTool(resources); }

        @Override
        protected ToolMessages.SearchFiles input() { return new ToolMessages.SearchFiles(".", "hello", true, 10); }

        @Override
        protected ToolMessages.SearchResult expected() { return SEARCH; }

        @Override
        protected ToolRequirements requirements() { return ToolRequirements.builder().read(Path.of(".")).build(); }
    }

    @Nested
    class Patch
            extends Binding<ToolMessages.ApplyPatch, ToolMessages.PatchResult>
    {
        @Override
        protected AgentTool<ToolMessages.ApplyPatch, ToolMessages.PatchResult> bind(Resources resources) { return new ApplyPatchTool(resources); }

        @Override
        protected ToolMessages.ApplyPatch input()
        {
            return new ToolMessages.ApplyPatch("note", ToolMessages.PatchAction.CREATE, Optional.empty(), List.of(new ToolMessages.Edit("", "hello")));
        }

        @Override
        protected ToolMessages.PatchResult expected() { return PATCH; }

        @Override
        protected ToolRequirements requirements() { return ToolRequirements.builder().write(Path.of("note")).build(); }
    }

    @Nested
    class Shell
            extends Binding<ToolMessages.ShellCommand, ToolMessages.CommandResult>
    {
        @Override
        protected AgentTool<ToolMessages.ShellCommand, ToolMessages.CommandResult> bind(Resources resources) { return new ShellCommandTool(resources); }

        @Override
        protected ToolMessages.ShellCommand input() { return new ToolMessages.ShellCommand(List.of("/usr/bin/true"), ".", 1); }

        @Override
        protected ToolMessages.CommandResult expected() { return COMMAND; }

        @Override
        protected ToolRequirements requirements() { return ToolRequirements.builder().read(Path.of(".")).require(ToolCapability.PROCESS_EXECUTE).build(); }

        @Override
        protected boolean preparesExternalResource() { return true; }
    }

    @Test
    void handlersRequireTheirBorrowedResources()
    {
        assertThatThrownBy(() -> new ReadFileTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListFilesTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SearchFilesTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApplyPatchTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShellCommandTool(null)).isInstanceOf(NullPointerException.class);
    }
}
