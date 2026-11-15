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

import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessIdentity;
import io.github.shiftinglabs.codej.cli.process.ProcessOutputSink;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class TestNativeCommandBackend
{
    @TempDir
    Path directory;
    private final CommandLimits limits = CommandLimits.defaults();
    private final String boot = UUID.randomUUID().toString();

    static ToolSetContext assembly(Path directory)
            throws IOException
    {
        return assembly(directory, new FakeCodingSupervisor());
    }

    static ToolSetContext assembly(Path directory, ProcessSupervisor supervisor)
            throws IOException
    {
        Path worktree = Files.createDirectories(directory.resolve("worktree"));
        Path profile = Files.createDirectories(directory.resolve("profile"));
        var connector = new ConnectorContext(profile, supervisor, ToolServer.DEFAULT);
        var session = new AgentSessionContext("task", worktree, directory.resolve("runtime"), directory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                Optional.empty(), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                Optional.empty(), Optional.empty(), SessionAccess.TOOLS_ONLY, "", List.of());
        return new ToolSetContext()
        {
            @Override
            public AgentSessionContext session() { return session; }
            @Override
            public ConnectorContext connector() { return connector; }
            @Override
            public Executor executor() { return Runnable::run; }
            @Override
            public Optional<ToolExecutionBudget> toolExecutionBudget() { return Optional.of(ToolExecutionBudget.bounded(1)); }
            @Override
            public Duration shutdownTimeout() { return Duration.ofMillis(100); }
        };
    }

    private ResourceTestContext context() { return new ResourceTestContext(AbstractTestCommandExecutor.grants()); }

    private static ToolMessages.ShellCommand command() { return new ToolMessages.ShellCommand(List.of("/bin/echo", "private $ % snow雪"), ".", 5); }

    static final class Service
            implements NativeCommandBackend.Service
    {
        final List<String> trace = new ArrayList<>();
        volatile CountDownLatch prepared = new CountDownLatch(1);
        volatile CountDownLatch payloadReady = new CountDownLatch(1);
        volatile boolean valid = true;
        volatile boolean verified = true;
        volatile boolean ready = true;
        volatile boolean automatic = true;
        volatile boolean observed;
        volatile int starts;
        volatile int payloads;
        volatile int acknowledgements;
        volatile Runtime current;
        ResourceTestContext context;
        NativeCommandLauncher.Request delivered;

        @Override
        public PreparedProcess prepare(NativeCommandRegistry.Entry entry, CommandLimits selected, Duration runtime)
        {
            assertThat(context.prepared).hasSize(1);
            trace.add("prepare");
            return new PreparedProcess()
            {
                private final String id = "launch-" + UUID.randomUUID();
                @Override
                public String launchId() { return id; }
                @Override
                public ManagedProcess start(ProcessOutputSink sink)
                {
                    assertThat(context.prepared).hasSize(2);
                    assertThat(entry.state().attempted()).isTrue();
                    starts++;
                    trace.add("start");
                    current = new Runtime(id, entry, sink);
                    if (ready) {
                        observed = true;
                        sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap(("codej-native-ready:" + entry.state().id() + "\n").getBytes(StandardCharsets.US_ASCII)));
                    }
                    prepared.countDown();
                    return current;
                }
            };
        }

        @Override
        public NativeUnit.Status inspect(NativeCommandRegistry.Entry entry)
        {
            return new NativeUnit.Status("loaded", "active", "codej-command-" + entry.state().id(), entry.state().root().toString(), "/test/" + entry.state().unit(), 1);
        }
        @Override
        public void validate(NativeCommandRegistry.Entry entry, NativeUnit.Status unit, CommandLimits selected)
                throws IOException
        {
            trace.add("validate");
            if (!valid) {
                throw new IOException("unverified quotas");
            }
        }
        @Override
        public boolean stop(NativeCommandRegistry.Entry entry, boolean creationSettled)
        {
            trace.add("stop");
            if (!verified || !observed && !creationSettled) {
                return false;
            }
            if (current != null) {
                current.finish();
            }
            return true;
        }
        @Override
        public boolean recover(String id)
        {
            trace.add("recover:" + id);
            return verified;
        }
        @Override
        public void acknowledge(String id)
        {
            acknowledgements++;
            trace.add("ack");
        }

        final class Runtime
                implements ManagedProcess
        {
            private final String id;
            private final NativeCommandRegistry.Entry entry;
            private final ProcessOutputSink sink;
            private final ByteArrayOutputStream input = new ByteArrayOutputStream();
            private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
            private boolean inputClosed;

            Runtime(String id, NativeCommandRegistry.Entry entry, ProcessOutputSink sink)
            {
                this.id = id;
                this.entry = entry;
                this.sink = sink;
            }
            @Override
            public String launchId() { return id; }
            @Override
            public long pid() { return 1; }
            @Override
            public Optional<List<ProcessIdentity>> observedDescendants() { return Optional.of(List.of()); }
            @Override
            public synchronized CompletionStage<Void> write(ByteBuffer bytes)
            {
                assertThat(inputClosed).isFalse();
                if (input.size() == 0) {
                    assertThat(trace).contains("validate");
                }
                else {
                    assertThat(entry.state().released()).isTrue();
                }
                byte[] chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                input.writeBytes(chunk);
                return CompletableFuture.completedStage(null);
            }
            @Override
            public synchronized void closeInput()
            {
                if (inputClosed) {
                    return;
                }
                inputClosed = true;
                try {
                    delivered = NativeCommandLauncher.read(new DataInputStream(new ByteArrayInputStream(input.toByteArray())), entry.state().id());
                    payloads++;
                    trace.add("payload");
                    payloadReady.countDown();
                    sink.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap("output".getBytes(StandardCharsets.UTF_8)));
                    if (automatic) {
                        finish();
                    }
                }
                catch (Exception incomplete) {
                    if (ready) {
                        finish();
                    }
                }
            }
            void finish() { exit.complete(new ProcessExit(0, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false)); }
            @Override
            public CompletionStage<ProcessExit> exit() { return exit.minimalCompletionStage(); }
            @Override
            public void cancel() { finish(); }
            @Override
            public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
            {
                return CompletableFuture.completedStage(verified ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE);
            }
            @Override
            public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished()
            {
                trace.add("controller-stopped");
                return stopAndVerify();
            }
            @Override
            public void close() { trace.add("close"); }
        }
    }

    private NativeCommandBackend backend(Service service, ResourceTestContext context)
            throws IOException
    {
        service.context = context;
        return new NativeCommandBackend(limits, CodingTools.defaults(), assembly(directory), service, boot);
    }

    private static ToolOutcome<ToolMessages.CommandResult> result(CommandExecution execution)
            throws Exception
    {
        return execution.completion().toCompletableFuture().get(4, TimeUnit.SECONDS);
    }

    @Test
    void gatesPayloadOnDurableOwnershipAndValidationThenAcknowledgesAfterCommit()
            throws Exception
    {
        var service = new Service();
        var context = context();
        try (var backend = backend(service, context)) {
            var execution = backend.startTree(command(), limits, context);
            assertThat(result(execution).value().orElseThrow().stdout()).isEqualTo("output");
            assertThat(service.delivered.argv()).isEqualTo(command().argv());
            assertThat(service.payloads).isEqualTo(1);
            assertThat(service.acknowledgements).isZero();
            assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
            assertThat(service.trace).containsSubsequence("prepare", "start", "validate", "payload", "stop", "controller-stopped");
            assertThatThrownBy(() -> backend.startTree(command(), limits, context())).isInstanceOf(IllegalStateException.class);
            execution.committed();
            execution.committed();
            assertThat(service.acknowledgements).isEqualTo(1);
            var nextContext = context();
            service.context = nextContext;
            service.automatic = false;
            service.prepared = new CountDownLatch(1);
            service.payloadReady = new CountDownLatch(1);
            var next = backend.startTree(command(), limits, nextContext);
            assertThat(service.prepared.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.payloadReady.await(2, TimeUnit.SECONDS)).isTrue();
            execution.cancel();
            execution.stopAndVerify();
            assertThat(next.completion().toCompletableFuture()).isNotDone();
            next.cancel();
            assertThat(result(next).state()).isEqualTo(ToolOutcome.State.COMPLETED);
            next.committed();
        }
    }

    @Test
    void rejectedJournalAndKernelValidationNeverDeliverACommand()
            throws Exception
    {
        var service = new Service();
        var context = context();
        context.failJournal = true;
        try (var backend = backend(service, context)) {
            var execution = backend.startTree(command(), limits, context);
            assertThat(result(execution).state()).isEqualTo(ToolOutcome.State.FAILED);
            assertThat(service.starts).isZero();
            execution.committed();
            context.failJournal = false;
            context.prepared.clear();
            service.valid = false;
            var denied = backend.startTree(command(), limits, context);
            assertThat(result(denied).state()).isEqualTo(ToolOutcome.State.FAILED);
            assertThat(service.payloads).isZero();
            denied.committed();
        }
    }

    @Test
    void uncertainStopRetainsOwnershipAndCloseCanRetryWithoutAcknowledgement()
            throws Exception
    {
        var service = new Service();
        var context = context();
        var backend = backend(service, context);
        service.verified = false;
        var execution = backend.startTree(command(), limits, context);
        assertThat(result(execution).state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
        assertThatThrownBy(execution::committed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(backend::close).isInstanceOf(IllegalStateException.class);
        assertThat(service.acknowledgements).isZero();
        service.verified = true;
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
        assertThatThrownBy(execution::committed).isInstanceOf(IllegalStateException.class);
        backend.close();
        backend.close();
        assertThat(service.acknowledgements).isZero();
    }

    @Test
    void cancellationBeforeReadinessDoesNotTreatAbsentCreationAsProofOfShutdown()
            throws Exception
    {
        var service = new Service();
        service.ready = false;
        var context = context();
        var backend = backend(service, context);
        var execution = backend.startTree(command(), limits, context);
        assertThat(service.prepared.await(2, TimeUnit.SECONDS)).isTrue();
        execution.cancel();
        assertThat(result(execution).state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
        assertThat(service.payloads).isZero();
        assertThat(service.acknowledgements).isZero();
        service.observed = true;
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
        backend.close();
    }

    @Test
    void observerCancellationCannotCancelOwnerAndDeadlineStopsPendingPayload()
            throws Exception
    {
        var service = new Service();
        service.automatic = false;
        var context = context();
        context.deadline = Optional.of(Instant.now().plusMillis(400));
        try (var backend = backend(service, context)) {
            var execution = backend.startTree(command(), limits, context);
            execution.completion().toCompletableFuture().cancel(true);
            var outcome = result(execution);
            assertThat(outcome.state()).isEqualTo(ToolOutcome.State.COMPLETED);
            assertThat(outcome.value().orElseThrow().reason()).isEqualTo(ProcessExit.Reason.TIMED_OUT);
            execution.committed();
        }
    }

    @Test
    void exportedLauncherContainsOnlyJreClassesAndNoModelRequest()
            throws Exception
    {
        var assembly = assembly(directory);
        Files.writeString(assembly.session().worktree().resolve("readme"), "content");
        try (var registry = new NativeCommandRegistry(assembly.connector().profileDirectory(), entry -> {})) {
            var entry = registry.create(boot);
            BuiltinCommands.artifacts(entry.state(), assembly.session().worktree(), List.of(), CodingTools.defaults(), context());
            try (var jar = new JarFile(Path.of(entry.state().directory()).resolve("launcher.jar").toFile())) {
                assertThat(jar.stream().map(java.util.jar.JarEntry::getName)).containsExactly(
                        NativeCommandLauncher.class.getName().replace('.', '/') + ".class", NativeCommandLauncher.Request.class.getName().replace('.', '/') + ".class");
            }
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-XX:ActiveProcessorCount=1", "-Xmx32m", "-XX:+UseSerialGC", "-cp", Path.of(entry.state().directory()).resolve("launcher.jar").toString(),
                    NativeCommandLauncher.class.getName(), entry.state().id());
            builder.environment().clear();
            Process launcher = builder.start();
            try {
                launcher.getOutputStream().close();
                assertThat(launcher.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(launcher.exitValue()).isEqualTo(126);
                assertThat(new String(launcher.getInputStream().readNBytes(256), StandardCharsets.UTF_8)).isEqualTo("codej-native-ready:" + entry.state().id() + "\n");
                assertThat(new String(launcher.getErrorStream().readNBytes(256), StandardCharsets.UTF_8)).isEqualTo("Native command could not start\n");
            }
            finally {
                launcher.destroyForcibly();
                assertThat(launcher.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(Files.readString(entry.state().root().resolve("workspace/readme"))).isEqualTo("content");
            entry.stopped();
            entry.remove(100);
        }
    }

    @Test
    void builtinFactoryIsPureAndUnsupportedHostsFailBeforePreparingProcesses()
            throws Exception
    {
        var config = BuiltinCommands.configuration(limits);
        assertThat(new CommandToolProvider().definitions(config)).containsExactly(RunCommandTool.DEFINITION);
        assertThatThrownBy(() -> BuiltinCommands.configuration(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BuiltinCommands.configuration(limits, null)).isInstanceOf(NullPointerException.class);
        assumeFalse(System.getProperty("os.name").equals("Linux"));
        var assembly = assembly(directory);
        assertThatThrownBy(() -> new CommandToolProvider().open(config, assembly)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Linux systemd");
        assertThat(((FakeCodingSupervisor) assembly.connector().processSupervisor()).specs).isEmpty();
    }
    @Test
    void abandonedUnitsAreStoppedButNeverInventACompletedResult()
            throws Exception
    {
        var assembly = assembly(directory);
        NativeCommandRegistry.Entry saved;
        try (var registry = new NativeCommandRegistry(assembly.connector().profileDirectory(), entry -> {})) {
            saved = registry.create(boot);
            saved.launch("abandoned-launch", true);
            saved.control("abandoned-control", true);
            saved.registered("/test/" + saved.state().unit());
        }
        var service = new Service();
        service.observed = true;
        try (var backend = new NativeCommandBackend(limits, CodingTools.defaults(), assembly, service, boot)) {
            assertThat(backend.supportsCommandTrees()).isTrue();
            assertThat(service.trace).containsSubsequence("recover:abandoned-control", "ack", "recover:abandoned-launch", "stop");
            assertThat(service.acknowledgements).isEqualTo(1);
            assertThat(Files.exists(Path.of(saved.state().directory()))).isFalse();
        }
        try (var files = Files.walk(assembly.connector().profileDirectory())) {
            var receipts = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertThat(receipts).hasSize(1);
            assertThat(Files.readString(receipts.getFirst())).contains("\"stopped\":true");
        }
    }

    @Test
    void oldBootReceiptsCannotSignalReusedProcessOrUnitIdentities()
            throws Exception
    {
        var assembly = assembly(directory);
        try (var registry = new NativeCommandRegistry(assembly.connector().profileDirectory(), entry -> {})) {
            var saved = registry.create(UUID.randomUUID().toString());
            saved.launch("old-launch", true);
        }
        var service = new Service();
        try (var backend = new NativeCommandBackend(limits, CodingTools.defaults(), assembly, service, boot)) {
            assertThat(backend.supportsCommandTrees()).isTrue();
            assertThat(service.trace).isEmpty();
        }
    }
}
