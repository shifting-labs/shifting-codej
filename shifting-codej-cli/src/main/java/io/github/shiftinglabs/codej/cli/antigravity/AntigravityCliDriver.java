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

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus.Readiness;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionOpenException;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.ProviderCli;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.io.IOException;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;

/** Adapts a locally installed Antigravity CLI to the connector SPI over ACP. */
public final class AntigravityCliDriver
        implements ConnectorDriver
{
    public static final AgentLaneId LANE = new AgentLaneId("antigravity");
    public static final String MINIMUM_VERSION = "1.5.10";
    private static final Pattern RELEASE = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration STOP_GRACE = Duration.ofSeconds(2);
    private static final int OUTPUT_LIMIT = 128 * 1024;

    private final String executable;
    private final ProcessSupervisor supervisor;
    private final Optional<Supplier<String>> apiKey;
    private final Path home;
    private final ConnectorIdentity identity;
    // An open that failed after launch keeps its cleanup here until shutdown has been verified.
    private final Map<Object, Runnable> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean checked;

    public AntigravityCliDriver(String executable, ConnectorContext context, Optional<Supplier<String>> apiKey)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.supervisor = requireNonNull(context, "context is null").processSupervisor();
        this.apiKey = requireNonNull(apiKey, "apiKey is null");
        // agy persists its model provider in settings.json. A key-based connection must never change an OAuth profile.
        this.home = context.profileDirectory().normalize().resolve(apiKey.isPresent() ? "gemini-api-key" : "google");
        String profile = Hashing.sha256().hashString(home.toString(), StandardCharsets.UTF_8).toString().substring(0, 16);
        this.identity = new ConnectorIdentity(LANE.value(), "profile-" + profile);
    }

    @Override
    public ConnectorIdentity identity()
    {
        return identity;
    }

    @Override
    public AgentLaneId id()
    {
        return LANE;
    }

    @Override
    public String label()
    {
        return "Antigravity CLI";
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return AgentCapabilities.none();
    }

    @Override
    public AgentProviderStatus probe()
    {
        Optional<String> version = Optional.empty();
        try {
            ensureOpen();
            String binary = resolveExecutable();
            Map<String, String> environment = environment();
            ProviderCli.Result release = run(binary, environment, "--version");
            if (release.exitCode() != 0 || release.truncated() || !RELEASE.matcher(release.stdout()).matches()) {
                return status(Readiness.TEMPORARILY_UNAVAILABLE, version, diagnostic("Unable to read the Antigravity CLI version", release, environment));
            }
            version = Optional.of(release.stdout());
            if (Version.parse(release.stdout()).compareTo(Version.parse(MINIMUM_VERSION)) < 0) {
                return status(Readiness.UNSUPPORTED, version, "Antigravity CLI " + MINIMUM_VERSION + " or newer is required");
            }
            ProviderCli.Result catalog = run(binary, environment, "models");
            if (catalog.exitCode() != 0 || catalog.truncated()) {
                if (!catalog.truncated() && catalog.combinedOutput().contains("Please sign in to view available models")) {
                    return status(Readiness.AUTHENTICATION_REQUIRED, version, "Sign in to this Antigravity profile or supply a Gemini API key");
                }
                return status(Readiness.TEMPORARILY_UNAVAILABLE, version, diagnostic("Antigravity could not list models for this profile", catalog, environment));
            }
            if (parseModels(catalog.stdout()).isEmpty()) {
                return status(Readiness.TEMPORARILY_UNAVAILABLE, version, "Antigravity returned an empty model catalog");
            }
            ProviderCli.Result agents = run(binary, environment, "agents");
            if (agents.exitCode() != 0 || agents.truncated() || agents.stdout().lines().noneMatch("codej-reader"::equals)) {
                return status(Readiness.UNSUPPORTED, version, "Antigravity could not load the SDK's read-only agent");
            }
            return status(Readiness.READY, version, "Antigravity CLI is ready");
        }
        catch (NoSuchFileException missing) {
            return status(Readiness.CLI_MISSING, version, "Antigravity CLI executable was not found: " + executable);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return status(Readiness.TEMPORARILY_UNAVAILABLE, version, "Antigravity CLI probe was interrupted");
        }
        catch (IOException | RuntimeException failure) {
            // Supplier exceptions and provider diagnostics can include the credential.
            return status(Readiness.TEMPORARILY_UNAVAILABLE, version, "Unable to prepare or probe the Antigravity CLI profile");
        }
    }

    private static String diagnostic(String message, ProviderCli.Result result, Map<String, String> environment)
    {
        if (result.truncated() || result.stderr().isBlank()) {
            return message;
        }
        String detail = SecretRedactor.fromEnvironment(environment).redact(result.stderr());
        return message + ": " + detail.substring(0, Math.min(detail.length(), 512));
    }

    private AgentProviderStatus status(Readiness readiness, Optional<String> version, String detail)
    {
        checked = readiness == Readiness.READY;
        Optional<String> login = readiness == Readiness.AUTHENTICATION_REQUIRED && apiKey.isEmpty()
                ? Optional.of("env -u GEMINI_API_KEY HOME=" + quote(home.toString()) + " GEMINI_FORCE_FILE_STORAGE=true " + quote(executable) + " acp --login")
                : Optional.empty();
        return new AgentProviderStatus(LANE, readiness, version, detail, capabilities(), login);
    }

    private static String quote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public List<ProviderModel> models()
    {
        ensureOpen();
        try {
            Map<String, String> environment = environment();
            ProviderCli.Result catalog = run(resolveExecutable(), environment, "models");
            if (catalog.exitCode() == 0 && !catalog.truncated()) {
                return parseModels(SecretRedactor.fromEnvironment(environment).redact(catalog.stdout()));
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to run Antigravity CLI", failure);
        }
        throw new AgentUnavailableException("Antigravity could not list models for this profile");
    }

    private static List<ProviderModel> parseModels(String stdout)
    {
        // agy models prints one slug and its display label per tab-separated line.
        return stdout.lines().map(line -> line.strip().split("\\t+", 2))
                .filter(row -> row.length == 2 && !row[0].isBlank() && !row[1].isBlank())
                .map(row -> new ProviderModel(row[0], row[1], "", List.of(), Optional.empty(), false, List.of("text"), List.of()))
                .toList();
    }

    private String resolveExecutable()
            throws NoSuchFileException
    {
        if (executable.contains("/")) {
            Path path = Path.of(executable);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        else {
            for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
                Path base = Path.of(directory);
                if (base.isAbsolute()) {
                    Path path = base.resolve(executable);
                    if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                        return path.toString();
                    }
                }
            }
        }
        throw new NoSuchFileException(executable);
    }

    private Map<String, String> environment()
            throws IOException
    {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        overrides.put("LANG", "en_US.UTF-8");
        if (apiKey.isPresent()) {
            String value;
            try {
                value = apiKey.orElseThrow().get();
            }
            catch (RuntimeException failure) {
                throw new AgentUnavailableException("Could not obtain the Gemini API key");
            }
            if (value == null || value.isBlank()) {
                throw new AgentUnavailableException("The Gemini API key supplier returned an empty key");
            }
            overrides.put("GEMINI_API_KEY", value);
        }
        return AntigravityPolicy.environment(home, overrides);
    }

    private ProviderCli.Result run(String binary, Map<String, String> environment, String argument)
            throws IOException, InterruptedException
    {
        ProcessSpec spec = new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "antigravity-cli"),
                AntigravityPolicy.command(List.of(binary, argument), home, home), home, environment, OptionalInt.of(OUTPUT_LIMIT), OUTPUT_LIMIT, PROBE_TIMEOUT, STOP_GRACE);
        return ProviderCli.run(supervisor, spec, label());
    }

    @Override
    public void validate(AgentSessionContext context)
    {
        requireNonNull(context, "context is null");
        ensureOpen();
        context.sessionReference().ifPresent(reference -> {
            if (!reference.laneId().equals(LANE) || reference.contractVersion() != 1) {
                throw new AgentUnavailableException("Unsupported Antigravity session reference");
            }
            verifyReference(reference, context.worktree());
        });
        AntigravityPolicy.validate(context);
    }

    @Override
    public synchronized AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        validate(context);
        requireNonNull(events, "events is null");
        Object token = new Object();
        try {
            AntigravityPolicy.checkDirectories(home, context.worktree());
            if (!checked) {
                AgentProviderStatus status = probe();
                if (!status.available()) {
                    throw new AgentUnavailableException(status.diagnostic());
                }
            }
            AntigravitySession session = AntigravitySession.open(supervisor, () -> sessionSpec(context), context, events, STARTUP_TIMEOUT,
                    id -> recordReference(id, context.worktree()), () -> sessions.remove(token));
            sessions.put(token, session::close);
            return session;
        }
        catch (AgentSessionOpenException failure) {
            sessions.put(token, failure::retryCleanup);
            throw failure;
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to prepare Antigravity session", failure);
        }
    }

    // ACP resume restores the original agent's tools even when --agent selects a different role.
    // Only accept sessions this connector opened for the same checkout. The child cannot write the config directory.
    private Path receipt(String id)
    {
        String name = Hashing.sha256().hashString(id, StandardCharsets.UTF_8).toString();
        return home.resolve(".gemini/config/codej-sessions").resolve(name);
    }

    private void recordReference(String id, Path checkout)
    {
        try {
            Files.writeString(receipt(id), checkout.toRealPath().toString(), CREATE_NEW, WRITE);
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to record the Antigravity session for this checkout", failure);
        }
    }

    private void verifyReference(AgentSessionRef reference, Path checkout)
    {
        Path receipt = receipt(reference.providerValue());
        try {
            if (Files.isRegularFile(receipt, NOFOLLOW_LINKS) && Files.readString(receipt).equals(checkout.toRealPath().toString())) {
                return;
            }
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to verify the saved Antigravity session", failure);
        }
        throw new AgentUnavailableException("This Antigravity session was not created by this connector for this checkout");
    }

    private ProcessSpec sessionSpec(AgentSessionContext context)
    {
        try {
            List<String> command = AntigravityPolicy.acpCommand(resolveExecutable());
            List<String> argv = AntigravityPolicy.command(command, home, context.worktree());
            return new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.DISCUSSION, context.taskId()), argv, context.worktree(),
                    environment(), OptionalInt.empty(), OUTPUT_LIMIT, Duration.ZERO, STOP_GRACE);
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to prepare Antigravity session", failure);
        }
    }

    private void ensureOpen()
    {
        if (closed) {
            throw new AgentUnavailableException("Antigravity driver is closed");
        }
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        RuntimeException failure = null;
        for (Runnable stop : List.copyOf(sessions.values())) {
            try {
                stop.run();
            }
            catch (RuntimeException problem) {
                if (failure == null) {
                    failure = problem;
                }
                else {
                    failure.addSuppressed(problem);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
