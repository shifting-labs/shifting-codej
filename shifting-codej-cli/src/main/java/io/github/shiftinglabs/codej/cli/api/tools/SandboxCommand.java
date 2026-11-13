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
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.CommandResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;

/** Seatbelt enforces read/write/network/process boundaries; the borrowed supervisor owns process lifecycle. */
final class SandboxCommand
        implements AutoCloseable
{
    private final WorkspaceFiles files;
    private final CodingTools limits;
    private final ProcessSupervisor supervisor;
    private final ProcessOwner owner;
    private final Consumer<String> prepared;
    private final Path scratch;
    private final String profile;
    private ManagedProcess active;
    private boolean stopped;

    SandboxCommand(WorkspaceFiles files, CodingTools limits, ProcessSupervisor supervisor, String owner, Consumer<String> prepared)
            throws IOException
    {
        if (!System.getProperty("os.name").equals("Mac OS X") || !Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
            throw new CodingToolException("SANDBOX_UNAVAILABLE", "Coding commands require macOS Seatbelt; no unsandboxed fallback is available");
        }
        this.files = files;
        this.limits = limits;
        this.supervisor = supervisor;
        this.owner = new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, owner);
        this.prepared = prepared;
        List<String> denied = files.sandboxDenials().stream().map(SandboxCommand::quote).toList();
        String workspace = quote(files.root());
        scratch = Files.createTempDirectory("codej-command-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        StringBuilder policy = new StringBuilder("""
                (version 1)
                (deny default)
                (deny file-link)
                (allow process-exec)
                (allow process-info* (target same-sandbox))
                (allow signal (target same-sandbox))
                (allow file-read* (literal "/"))
                (allow file-read-metadata)
                (allow sysctl-read (sysctl-name "hw.ncpu") (sysctl-name "hw.activecpu") (sysctl-name "hw.logicalcpu") (sysctl-name "hw.memsize"))
                (allow file-read* (subpath "/System/Library") (subpath "/System/Cryptexes") (subpath "/usr/lib") (subpath "/usr/share") (subpath "/usr/bin") (subpath "/bin")
                    (literal "/dev/null") (literal "/dev/urandom"))
                """);
        policy.append("(allow file-read* (subpath ").append(workspace).append(") (subpath ").append(quote(scratch)).append("))\n");
        policy.append("(allow file-write* (subpath ").append(quote(scratch)).append(") (literal \"/dev/null\"))\n");
        policy.append("(deny file-write-unlink (literal ").append(quote(scratch)).append("))\n");
        policy.append("(deny file-read* (regex #\"").append(WorkspaceFiles.protectedPattern(files.root())).append("\"))\n");
        for (String path : denied) {
            policy.append("(deny file-read* (subpath ").append(path).append("))\n");
        }
        if (policy.length() > 65536) {
            Files.delete(scratch);
            throw new CodingToolException("ENTRY_LIMIT", "Workspace exclusions exceed the command policy limit");
        }
        profile = policy.toString();
    }

    void check()
            throws IOException
    {
        CommandResult result = run(new ShellCommand(List.of("/usr/bin/true"), ".", Math.min(5, limits.maximumCommandSeconds())));
        if (result.exitCode() != 0 || result.reason() != ProcessExit.Reason.EXITED) {
            throw new CodingToolException("SANDBOX_UNAVAILABLE", "The command sandbox could not be established");
        }
    }

    CommandResult run(ShellCommand command)
            throws IOException
    {
        if (active != null) {
            throw new CodingToolException("PROCESS_UNCERTAIN", "Previous command requires reconciliation", true);
        }
        if (command.timeoutSeconds() > limits.maximumCommandSeconds()) {
            throw new CodingToolException("COMMAND_LIMIT", "Command exceeds its configured timeout");
        }
        Path cwd = files.commandDirectory(command.directory());
        List<String> argv = new ArrayList<>(List.of("/usr/bin/sandbox-exec", "-p", profile));
        argv.addAll(command.argv());
        ProcessSpec spec = new ProcessSpec(owner, argv, cwd,
                Map.of("PATH", "/usr/bin:/bin", "LANG", "en_US.UTF-8", "LC_ALL", "en_US.UTF-8", "HOME", scratch.toString(), "TMPDIR", scratch.toString()),
                OptionalInt.of(limits.maximumOutputBytes()), limits.maximumOutputBytes(), Duration.ofSeconds(command.timeoutSeconds()), Duration.ofMillis(200));
        var launch = supervisor.prepare(spec);
        try {
            prepared.accept(launch.launchId());
        }
        catch (RuntimeException failure) {
            supervisor.acknowledge(launch.launchId());
            throw failure;
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        active = launch.start((stream, bytes) -> {
            ByteArrayOutputStream target = stream == ProcessStream.STDOUT ? stdout : stderr;
            synchronized (target) {
                int count = Math.min(bytes.remaining(), Math.max(0, limits.maximumOutputBytes() - target.size()));
                byte[] chunk = new byte[count];
                bytes.get(chunk);
                target.writeBytes(chunk);
            }
        });
        ProcessExit exit;
        try {
            active.closeInput();
            exit = active.exit().toCompletableFuture().get(command.timeoutSeconds() + 5L, TimeUnit.SECONDS);
            // Seatbelt forbids fork/spawn, so root exit proves completion of all work in this launch.
            stopped = active.stopAfterWorkFinished().toCompletableFuture().get(3, TimeUnit.SECONDS) == ProcessExit.TreeTermination.VERIFIED_STOPPED;
        }
        catch (InterruptedException failure) {
            active.cancel();
            verifyStop();
            throw new CodingToolException("INTERRUPTED", "Command interrupted");
        }
        catch (Exception failure) {
            active.cancel();
            verifyStop();
            throw new CodingToolException("COMMAND_FAILED", "Command did not complete normally");
        }
        if (!stopped) {
            throw new CodingToolException("PROCESS_UNCERTAIN", "Command termination could not be verified", true);
        }
        return new CommandResult(exit.exitCode(), exit.reason(), stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
                exit.stdoutTruncated(), exit.stderrTruncated());
    }

    private void verifyStop()
    {
        boolean interrupted = Thread.interrupted();
        try {
            stopped = active.stopAndVerify().toCompletableFuture().get(3, TimeUnit.SECONDS) == ProcessExit.TreeTermination.VERIFIED_STOPPED;
        }
        catch (Exception failure) {
            throw new CodingToolException("PROCESS_UNCERTAIN", "Command termination could not be verified", true);
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!stopped) {
            throw new CodingToolException("PROCESS_UNCERTAIN", "Command termination could not be verified", true);
        }
    }

    void committed()
    {
        if (active != null && stopped) {
            supervisor.acknowledge(active.launchId());
            active.close();
            active = null;
            stopped = false;
        }
    }

    private static String quote(Path path)
    {
        String value = path.toString();
        if (value.codePoints().anyMatch(character -> character < 32 || character == 127)) {
            throw new CodingToolException("PATH_DENIED", "Control characters are unsupported in sandbox paths");
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close()
            throws IOException
    {
        if (active != null) {
            verifyStop();
            // Keep the durable marker until the engine has saved a command result; recovery may still need it.
            active.close();
            active = null;
        }
        if (Files.exists(scratch, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            deleteScratch(scratch, 0, new int[1]);
        }
    }

    private void deleteScratch(Path path, int depth, int[] visited)
            throws IOException
    {
        if (++visited[0] > limits.maximumEntries() || depth > 64) {
            throw new IOException("scratch cleanup limit exceeded");
        }
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            try (var entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteScratch(entry, depth + 1, visited);
                }
            }
        }
        Files.delete(path);
    }
}
