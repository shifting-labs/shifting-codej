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
package io.github.shiftinglabs.codej.cli.antigravity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.process.GitWorktreeWriteRoots;
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.github.shiftinglabs.codej.cli.antigravity.AntigravityPolicy.quote;
import static io.github.shiftinglabs.codej.cli.process.ProcessExit.TreeTermination.VERIFIED_STOPPED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Runs workspace commands in a separate Seatbelt profile from the credentialed ACP process. */
final class AntigravityCommands
{
    static final String NAME = "codej_run_command";
    private static final int OUTPUT_LIMIT = 32 * 1024;
    private static final Duration RUNTIME_LIMIT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["argv"],
            "properties":{"argv":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"string"},
            "description":"Executable followed by its individual arguments"}}}
            """;
    private static final String PROFILE = """
            (version 1)
            (allow default)
            (deny file-read-data)
            (allow file-read-data (literal "/") (subpath "/System/Library") (subpath "/System/Cryptexes")
                (subpath "/usr/lib") (subpath "/usr/share") (subpath "/usr/bin") (subpath "/bin") (subpath "/sbin")
                (subpath "/dev") (subpath "/private/etc") (subpath "/Library/Preferences")
                (subpath "/Library/Java/JavaVirtualMachines") (subpath "/Library/Developer/CommandLineTools")
                (subpath "/Applications/Xcode.app/Contents") (literal "/private/var/db/xcode_select_link")
                (subpath "/opt/homebrew/bin") (subpath "/opt/homebrew/Cellar") (subpath "/opt/homebrew/opt")
                (subpath "/usr/local/bin") (subpath "/usr/local/Cellar") (subpath "/usr/local/opt"))
            (deny file-write*)
            (allow file-write* (literal "/dev/null"))
            (deny signal (target others))
            """;

    private final ProcessSupervisor supervisor;
    private final ProcessSpec provider;
    private final AgentPermissionScope scope;
    private final Path runtime;
    private final ReentrantLock lock = new ReentrantLock();
    private ManagedProcess process;
    private Path scratch;

    AntigravityCommands(ProcessSupervisor supervisor, ProcessSpec provider, AgentPermissionScope scope, Path runtime)
    {
        this.supervisor = supervisor;
        this.provider = provider;
        this.scope = scope;
        this.runtime = runtime;
    }

    static Tool tool(Tool.Handler<String> handler)
    {
        return Tool.withSchema(NAME, "Run a sandboxed build or test command from the checkout. Pass an executable and its arguments as argv.", SCHEMA, handler);
    }

    String run(String input, ProviderAgentEventSource source, SecretRedactor redactor,
            Function<ProviderApprovalRequest, CompletableFuture<ProviderApprovalDecision>> ask)
            throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        List<String> argv = arguments(input);
        String display = String.join(" ", argv);
        Path executable = Path.of(argv.getFirst()).getFileName();
        if (executable == null) {
            throw new AgentUnavailableException("A command requires an executable");
        }
        List<String> byName = ImmutableList.<String>builder().add(executable.toString()).addAll(argv.subList(1, argv.size())).build();
        String namedCommand = String.join(" ", byName);
        if (scope.deniedCommands().stream().map(String::strip)
                .anyMatch(prefix -> matchesPrefix(display, prefix) || matchesPrefix(namedCommand, prefix))) {
            throw new AgentUnavailableException("Command is denied by the session policy");
        }
        if (!lock.tryLock()) {
            throw new AgentUnavailableException("Another Antigravity command is active");
        }
        try {
            if (process != null) {
                throw new AgentUnavailableException("The previous Antigravity command could not be verified stopped");
            }
            if (scope.approveEveryCommand()) {
                String command = redactor.redact(display);
                var approval = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.COMMAND, "Run sandboxed command",
                        command, ImmutableList.of("execute"), ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY),
                        Optional.empty(), true, source, Instant.now(), Optional.empty(), Optional.of(command));
                CompletableFuture<ProviderApprovalDecision> decision = ask.apply(approval);
                try {
                    if (decision.get().decision() != ProviderApprovalDecision.Decision.ALLOW) {
                        throw new AgentUnavailableException("Command approval was denied");
                    }
                }
                finally {
                    decision.cancel(false);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Command cancelled");
            }
            scratch = Files.createTempDirectory("codej-agy-command-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
            return execute(argv, byName);
        }
        finally {
            try {
                if (!stop()) {
                    throw new AgentUnavailableException("Antigravity command process tree could not be verified stopped");
                }
            }
            finally {
                lock.unlock();
            }
        }
    }

    private static List<String> arguments(String input)
            throws IOException
    {
        var root = JSON.readTree(input);
        var items = root.path("argv");
        if (root.size() != 1 || !items.isArray() || items.isEmpty() || items.size() > 128) {
            throw new AgentUnavailableException("A command requires an argv array with 1 to 128 arguments");
        }
        ImmutableList.Builder<String> argv = ImmutableList.builder();
        for (var item : items) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().codePoints().anyMatch(Character::isISOControl)) {
                throw new AgentUnavailableException("Command arguments must be nonblank strings without control characters");
            }
            argv.add(item.asText());
        }
        return argv.build();
    }

    private static boolean matchesPrefix(String command, String prefix)
    {
        return command.equals(prefix) || command.startsWith(prefix + " ");
    }

    private String execute(List<String> argv, List<String> byName)
            throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        Path worktree = provider.workingDirectory();
        boolean localGit = scope.commits() && byName.getFirst().equals("git") && byName.size() > 1 &&
                (byName.get(1).equals("add") || byName.get(1).equals("commit"));
        String profile = profile(worktree, localGit);
        var environment = ImmutableMap.<String, String>builder()
                .put("PATH", provider.environment().get("PATH"))
                .put("LANG", provider.environment().get("LANG"))
                .put("HOME", scratch.toString())
                .put("TMPDIR", scratch.toString())
                .put("GIT_CONFIG_NOSYSTEM", "1")
                .put("GIT_CONFIG_COUNT", "1")
                .put("GIT_CONFIG_KEY_0", "core.hooksPath")
                .put("GIT_CONFIG_VALUE_0", "/dev/null")
                .buildOrThrow();
        List<String> command = ImmutableList.<String>builder().add("/usr/bin/sandbox-exec", "-p", profile, "--").addAll(argv).build();
        ProcessSpec spec = new ProcessSpec(provider.owner(), command, worktree, environment,
                OptionalInt.of(OUTPUT_LIMIT), OUTPUT_LIMIT, RUNTIME_LIMIT, Duration.ofMillis(200));
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        process = supervisor.prepare(spec).start((stream, bytes) -> {
            byte[] chunk = new byte[bytes.remaining()];
            bytes.get(chunk);
            (stream == ProcessStream.STDOUT ? stdout : stderr).writeBytes(chunk);
        });
        process.closeInput();
        ProcessExit exit = process.exit().toCompletableFuture().get(RUNTIME_LIMIT.toSeconds() + 5, SECONDS);
        return AcpClient.encode(new CommandResult(exit.exitCode(), exit.reason(), stdout.toString(UTF_8), stderr.toString(UTF_8),
                exit.stdoutTruncated() || exit.stderrTruncated()));
    }

    private String profile(Path worktree, boolean localGit)
            throws IOException
    {
        Path home = Path.of(provider.environment().get("HOME"));
        Path runtimePath = AntigravityPolicy.canonicalPath(runtime);
        StringBuilder rules = new StringBuilder(PROFILE);
        directoryRules(rules, worktree);
        directoryRules(rules, scratch);
        for (Path root : scope.writableRoots()) {
            Path target = AntigravityPolicy.canonicalPath(root);
            checkWritableRoot(target, home, runtimePath);
            directoryRules(rules, target);
        }
        rules.append("(deny file-read* file-read-data file-write* (subpath ").append(quote(home)).append(") (subpath ").append(quote(runtimePath)).append("))\n")
                .append("(deny file-write* (regex #\"/[.][gG][iI][tT](/|$)\"))\n");
        if (localGit) {
            gitRules(rules, worktree);
        }
        if (!scope.network() || localGit) {
            rules.append("(deny network*)\n");
        }
        return rules.append("(deny file-link)\n").append(AntigravityPolicy.PROTECTED_READS)
                .append(AntigravityPolicy.denyReads(scope.sessionDeniedReads())).toString();
    }

    static void validatePaths(AgentSessionContext context, Path providerHome)
            throws IOException
    {
        var scope = context.scope();
        if (!scope.writableRoots().isEmpty()) {
            Path home = AntigravityPolicy.canonicalPath(providerHome);
            Path runtime = AntigravityPolicy.canonicalPath(context.runtimeDirectory());
            for (Path root : scope.writableRoots()) {
                checkWritableRoot(AntigravityPolicy.canonicalPath(root), home, runtime);
            }
        }
        if (scope.commits()) {
            gitRoots(context.worktree());
        }
    }

    private static void checkWritableRoot(Path path, Path home, Path runtime)
    {
        if (path.startsWith(home) || home.startsWith(path) || path.startsWith(runtime) || runtime.startsWith(path)) {
            throw new AgentUnavailableException("Antigravity writable roots cannot overlap provider or session state");
        }
    }

    private static void directoryRules(StringBuilder rules, Path directory)
    {
        String root = quote(directory);
        rules.append("(allow file-read-data file-write* (subpath ").append(root).append("))\n")
                .append("(deny file-write-unlink (literal ").append(root).append("))\n");
    }

    private static void gitRules(StringBuilder rules, Path worktree)
    {
        List<String> roots = gitRoots(worktree);
        Path privateGit = Path.of(roots.get(1));
        rules.append("(allow file-read-data (subpath ").append(quote(privateGit.getParent().getParent())).append("))\n")
                .append("(allow file-write* (subpath ").append(quote(privateGit)).append(") (subpath ")
                .append(quote(Path.of(roots.get(2)))).append(")");
        roots.subList(3, roots.size()).forEach(path -> rules.append(" (literal ").append(quote(Path.of(path))).append(")"));
        rules.append(")\n");
        for (String file : ImmutableList.<String>builder().addAll(GitWorktreeWriteRoots.PROTECTED_METADATA).add("HEAD").build()) {
            rules.append("(deny file-write* (subpath ").append(quote(privateGit.resolve(file))).append("))\n");
        }
        rules.append("(deny file-write-unlink (literal ").append(quote(privateGit)).append("))\n");
    }

    private static List<String> gitRoots(Path worktree)
    {
        try {
            List<String> roots = GitWorktreeWriteRoots.writableRoots(worktree);
            if (roots.size() > 3) {
                return roots;
            }
        }
        catch (IllegalStateException failure) {
            throw new AgentUnavailableException("Antigravity commits require a linked worktree on a named local branch", failure);
        }
        throw new AgentUnavailableException("Antigravity commits require a linked worktree on a named local branch");
    }

    boolean stop()
    {
        if (!lock.tryLock()) {
            return false;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null) {
                if (process.stopAndVerify().toCompletableFuture().get(2, SECONDS) != VERIFIED_STOPPED) {
                    return false;
                }
                supervisor.acknowledge(process.launchId());
                process.close();
                process = null;
            }
            if (scratch != null) {
                MoreFiles.deleteRecursively(scratch, ALLOW_INSECURE);
                scratch = null;
            }
            return true;
        }
        catch (InterruptedException failure) {
            interrupted = true;
            return false;
        }
        catch (ExecutionException | TimeoutException | IOException | RuntimeException failure) {
            return false;
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            lock.unlock();
        }
    }

    private record CommandResult(int exitCode, ProcessExit.Reason reason, String stdout, String stderr, boolean truncated) {}
}
