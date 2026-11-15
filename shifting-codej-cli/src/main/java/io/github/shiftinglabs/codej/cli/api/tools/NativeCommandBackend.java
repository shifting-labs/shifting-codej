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
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/** One virtual worker per admitted invocation, using the borrowed supervisor and OS isolation service. */
final class NativeCommandBackend
        implements CommandToolProvider.Backend
{
    interface Service
    {
        PreparedProcess prepare(NativeCommandRegistry.Entry entry, CommandLimits limits, Duration runtime)
                throws IOException;

        NativeUnit.Status inspect(NativeCommandRegistry.Entry entry)
                throws IOException;

        void validate(NativeCommandRegistry.Entry entry, NativeUnit.Status status, CommandLimits limits)
                throws IOException;

        boolean stop(NativeCommandRegistry.Entry entry, boolean creationSettled)
                throws IOException;

        boolean recover(String launch);

        void acknowledge(String launch);
    }

    private final CommandLimits limits;
    private final CodingTools workspaceLimits;
    private final ToolSetContext assembly;
    private final Service service;
    private final String boot;
    private final NativeCommandRegistry registry;
    private Execution active;
    private boolean closed;

    NativeCommandBackend(CommandLimits limits, CodingTools workspaceLimits, ToolSetContext assembly, Service service, String boot)
            throws IOException
    {
        this.limits = requireNonNull(limits, "limits is null");
        this.workspaceLimits = requireNonNull(workspaceLimits, "workspaceLimits is null");
        this.assembly = requireNonNull(assembly, "assembly is null");
        this.service = requireNonNull(service, "service is null");
        this.boot = requireNonNull(boot, "boot is null");
        registry = new NativeCommandRegistry(assembly.connector().profileDirectory(), this::recover);
    }

    private void recover(NativeCommandRegistry.Entry entry)
            throws IOException
    {
        var state = entry.state();
        if (!state.stopped()) {
            if (state.boot().equals(boot)) {
                for (String control : state.controls()) {
                    if (!service.recover(control)) {
                        throw new IOException("Native control recovery is unverified");
                    }
                    entry.control(control, false);
                    service.acknowledge(control);
                }
                if (state.launch().isPresent() && !service.recover(state.launch().orElseThrow())) {
                    throw new IOException("Native controller recovery is unverified");
                }
                if (state.attempted() && !service.stop(entry, false)) {
                    throw new IOException("Native unit recovery is unverified");
                }
            }
            entry.stopped();
        }
        NativeCommandRegistry.cleanup(entry.state(), workspaceLimits.maximumEntries() + 256);
        // Keep the stopped receipt and main launch marker: a crash did not commit an engine tool result.
    }

    @Override
    public boolean supportsCommandTrees() { return true; }

    @Override
    public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
    {
        throw new UnsupportedOperationException("Use the existing shell backend for single-process commands");
    }

    @Override
    public synchronized CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits selected, ToolExecutionContext context)
    {
        requireNonNull(request, "request is null");
        if (closed || active != null || !limits.equals(selected) || request.timeoutSeconds() > limits.maximumSeconds()) {
            throw new IllegalStateException("Native backend is closed, busy or configured for different limits");
        }
        CodingToolSupport.check(CodingToolSupport.command(), context);
        Instant deadline = Instant.now().plusSeconds(request.timeoutSeconds());
        if (context.deadline().isPresent() && context.deadline().orElseThrow().isBefore(deadline)) {
            deadline = context.deadline().orElseThrow();
        }
        active = new Execution(request, context, deadline);
        active.worker = Thread.ofVirtual().name("codej-native-command").start(active::run);
        return active;
    }

    private final class Execution
            implements CommandExecution
    {
        private final ToolMessages.ShellCommand request;
        private final ToolExecutionContext context;
        private final Instant deadline;
        private final CompletableFuture<ToolOutcome<ToolMessages.CommandResult>> done = new CompletableFuture<>();
        private final AtomicBoolean cancel = new AtomicBoolean();
        private final Capture capture = new Capture(limits.maximumOutputBytes());
        private volatile Thread worker;
        private volatile NativeCommandRegistry.Entry entry;
        private volatile ManagedProcess process;
        private volatile boolean verified;
        private volatile boolean acknowledged;
        private volatile boolean creationSettled;
        private CompletableFuture<ToolStopResult> stopping = new CompletableFuture<>();

        Execution(ToolMessages.ShellCommand request, ToolExecutionContext context, Instant deadline)
        {
            this.request = request;
            this.context = context;
            this.deadline = deadline;
        }

        private void checkActive()
        {
            context.checkActive();
            if (cancel.get() || !deadline.isAfter(Instant.now())) {
                throw new CancellationException("Native command cancelled or deadline exceeded");
            }
        }

        private Duration remaining()
        {
            return Duration.ofMillis(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()));
        }

        void run()
        {
            ToolOutcome<ToolMessages.CommandResult> outcome;
            ProcessExit exit = null;
            boolean timedOut = false;
            try {
                checkActive();
                entry = registry.create(boot);
                context.recordPrepared(new ToolResource("command.unit", entry.state().id()));
                BuiltinCommands.artifacts(entry.state(), assembly.session().worktree(),
                        List.of(assembly.connector().profileDirectory(), assembly.session().runtimeDirectory()), workspaceLimits, new DeadlineContext(context, deadline));
                checkActive();
                PreparedProcess prepared = service.prepare(entry, limits, remaining());
                entry.launch(prepared.launchId(), false);
                context.recordPrepared(new ToolResource("process.launch", prepared.launchId()));
                checkActive();
                entry.launch(prepared.launchId(), true);
                capture.nonce = entry.state().id();
                process = prepared.start(capture::accept);
                awaitReady();
                checkActive();
                NativeUnit.Status status = service.inspect(entry);
                if (!status.absent()) {
                    entry.registered(status.group());
                    creationSettled = true;
                }
                service.validate(entry, status, limits);
                checkActive();
                byte[] frame = NativeCommandLauncher.encode(entry.state().id(), new NativeCommandLauncher.Request(request.argv(), request.directory()));
                process.write(ByteBuffer.wrap(frame)).toCompletableFuture().get(remaining().toMillis(), TimeUnit.MILLISECONDS);
                checkActive();
                entry.released();
                process.write(ByteBuffer.wrap(new byte[] {(byte) NativeCommandLauncher.COMMIT})).toCompletableFuture().get(remaining().toMillis(), TimeUnit.MILLISECONDS);
                process.closeInput();
                while (true) {
                    checkActive();
                    try {
                        exit = process.exit().toCompletableFuture().get(Math.min(50, remaining().toMillis()), TimeUnit.MILLISECONDS);
                        creationSettled = exit.reason() == ProcessExit.Reason.EXITED || creationSettled;
                        break;
                    }
                    catch (TimeoutException waiting) {
                    }
                }
                if (!verify()) {
                    throw new IOException("Native shutdown is unverified");
                }
                CommandOutput output = output();
                outcome = ToolOutcome.completed(new ToolMessages.CommandResult(exit.exitCode(), exit.reason(), output.stdout(), output.stderr(),
                        output.stdoutTruncated() || exit.stdoutTruncated(), output.stderrTruncated() || exit.stderrTruncated()));
            }
            catch (Exception failure) {
                timedOut = !deadline.isAfter(Instant.now());
                Thread.interrupted();
                boolean stopped = verify();
                if (!stopped) {
                    outcome = ToolOutcome.uncertain(new ToolFailure("NATIVE_UNCERTAIN", "Native command shutdown or launch could not be verified"));
                }
                else if (timedOut || cancel.get() || context.cancellationRequested()) {
                    CommandOutput output = output();
                    outcome = ToolOutcome.completed(new ToolMessages.CommandResult(-1, timedOut ? ProcessExit.Reason.TIMED_OUT : ProcessExit.Reason.CANCELLED,
                            output.stdout(), output.stderr(), output.stdoutTruncated(), output.stderrTruncated()));
                }
                else {
                    outcome = ToolOutcome.failed(new ToolFailure("NATIVE_UNAVAILABLE", "Native isolation, resource limits or command startup could not be verified"));
                }
            }
            synchronized (this) {
                stopping.complete(verified ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Native command remains owned"));
            }
            done.complete(outcome);
        }

        private void awaitReady()
                throws Exception
        {
            Instant readyDeadline = Instant.now().plusSeconds(5);
            while (true) {
                checkActive();
                if (!readyDeadline.isAfter(Instant.now())) {
                    throw new IOException("Native launcher did not become ready");
                }
                try {
                    capture.ready.get(25, TimeUnit.MILLISECONDS);
                    creationSettled = true;
                    return;
                }
                catch (TimeoutException waiting) {
                    if (process.exit().toCompletableFuture().isDone()) {
                        creationSettled = process.exit().toCompletableFuture().get().reason() == ProcessExit.Reason.EXITED;
                        throw new IOException("Native launcher did not become ready");
                    }
                }
            }
        }

        private synchronized boolean verify()
        {
            if (verified) {
                return true;
            }
            boolean interrupted = Thread.interrupted();
            try {
                if (entry == null) {
                    verified = true;
                    return true;
                }
                if (process != null) {
                    process.closeInput();
                    if (!entry.state().registered() && !creationSettled) {
                        try {
                            var exit = process.exit().toCompletableFuture().get(assembly.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
                            creationSettled = exit.reason() == ProcessExit.Reason.EXITED;
                        }
                        catch (TimeoutException unresolved) {
                            // An unacknowledged service creation remains owned if the manager cannot fence it.
                        }
                    }
                }
                if (!entry.state().controls().isEmpty()) {
                    for (String control : entry.state().controls()) {
                        if (!service.recover(control)) {
                            return false;
                        }
                        entry.control(control, false);
                        service.acknowledge(control);
                    }
                }
                if (entry.state().attempted() && !service.stop(entry, creationSettled)) {
                    return false;
                }
                if (process != null) {
                    if (process.stopAfterWorkFinished().toCompletableFuture().get(assembly.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)
                            != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                        return false;
                    }
                }
                else if (entry.state().launch().isPresent() && entry.state().attempted() && !service.recover(entry.state().launch().orElseThrow())) {
                    return false;
                }
                entry.stopped();
                verified = true;
                return true;
            }
            catch (Exception unavailable) {
                return false;
            }
            finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public CommandOutput output() { return capture.output(); }

        @Override
        public CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion() { return done.minimalCompletionStage(); }

        @Override
        public void cancel()
        {
            if (!verified) {
                cancel.set(true);
                Thread running = worker;
                if (running != null) {
                    running.interrupt();
                }
            }
        }

        @Override
        public synchronized CompletionStage<ToolStopResult> stopAndVerify()
        {
            if (verified) {
                return CompletableFuture.completedStage(ToolStopResult.verifiedStopped());
            }
            cancel();
            if (done.isDone() && stopping.isDone()) {
                stopping = new CompletableFuture<>();
                Thread.ofVirtual().name("codej-native-stop").start(() -> {
                    boolean stopped = verify();
                    synchronized (Execution.this) {
                        stopping.complete(stopped ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Native command remains owned"));
                    }
                });
            }
            return stopping.minimalCompletionStage();
        }

        @Override
        public void committed()
        {
            if (!verified || !done.isDone() || done.join().state() == ToolOutcome.State.UNCERTAIN) {
                throw new IllegalStateException("Native command outcome or shutdown remains unresolved");
            }
            synchronized (NativeCommandBackend.this) {
                if (acknowledged) {
                    return;
                }
                try {
                    if (entry != null) {
                        entry.remove(workspaceLimits.maximumEntries() + 256);
                        entry.state().launch().ifPresent(service::acknowledge);
                    }
                    if (process != null) {
                        process.close();
                    }
                    acknowledged = true;
                    if (active == this) {
                        active = null;
                    }
                }
                catch (IOException failure) {
                    throw new IllegalStateException("Native command resources remain owned");
                }
            }
        }
    }

    private record DeadlineContext(ToolExecutionContext delegate, Instant maximum)
            implements ToolExecutionContext
    {
        @Override
        public String invocationId() { return delegate.invocationId(); }
        @Override
        public Optional<Instant> deadline() { return Optional.of(maximum); }
        @Override
        public io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements grants() { return delegate.grants(); }
        @Override
        public boolean cancellationRequested() { return delegate.cancellationRequested(); }
        @Override
        public void recordPrepared(ToolResource resource) { delegate.recordPrepared(resource); }
    }

    static final class Capture
    {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        volatile String nonce;
        private final int maximum;
        private final ByteArrayOutputStream header = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private boolean truncatedOut;
        private boolean truncatedErr;

        Capture(int maximum) { this.maximum = maximum; }

        synchronized void accept(ProcessStream stream, ByteBuffer bytes)
        {
            if (stream == ProcessStream.STDOUT && !ready.isDone()) {
                while (bytes.hasRemaining()) {
                    byte value = bytes.get();
                    if (value == '\n') {
                        if (header.toString(StandardCharsets.US_ASCII).equals("codej-native-ready:" + nonce)) {
                            ready.complete(null);
                        }
                        else {
                            ready.completeExceptionally(new IOException("Invalid native readiness frame"));
                        }
                        break;
                    }
                    if (header.size() >= 128) {
                        ready.completeExceptionally(new IOException("Oversized native readiness frame"));
                        break;
                    }
                    header.write(value);
                }
            }
            ByteArrayOutputStream target = stream == ProcessStream.STDOUT ? stdout : stderr;
            int accepted = Math.min(bytes.remaining(), Math.max(0, maximum - target.size()));
            if (accepted < bytes.remaining()) {
                if (stream == ProcessStream.STDOUT) {
                    truncatedOut = true;
                }
                else {
                    truncatedErr = true;
                }
            }
            byte[] chunk = new byte[accepted];
            bytes.get(chunk);
            target.writeBytes(chunk);
        }

        synchronized CommandOutput output()
        {
            return new CommandOutput(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), truncatedOut, truncatedErr);
        }
    }

    @Override
    public synchronized void close()
    {
        if (closed) {
            return;
        }
        try {
            if (active != null) {
                ToolStopResult result = active.stopAndVerify().toCompletableFuture().get(assembly.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!result.stopped()) {
                    throw new IOException("Native shutdown remains unresolved");
                }
                if (active.entry != null) {
                    NativeCommandRegistry.cleanup(active.entry.state(), workspaceLimits.maximumEntries() + 256);
                }
                if (active.process != null) {
                    active.process.close();
                }
            }
            registry.close();
            closed = true;
        }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Native command resources remain owned");
        }
    }
}
