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
package io.github.shiftinglabs.codej.sdk.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Capabilities;
import io.github.shiftinglabs.codej.Limits;
import io.github.shiftinglabs.codej.Model;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.cli.codex.CodexGateway;
import io.github.shiftinglabs.codej.cli.codex.CodexHome;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** An {@link Agent} over one of the provider drivers. Not public API: {@code Codex} and {@code Claude} build it. */
public final class DriverAgent
        implements Agent
{
    private static final Path DEFAULT_STATE = Path.of(System.getProperty("user.home"), "Library", "Application Support", "shifting-codej");
    private static final Executor VIRTUAL_THREADS = command -> Thread.ofVirtual().start(command);

    private final AgentDriver driver;
    private final String provider;
    private final StateFolder state;
    /** This run's sessions whose process could not be shown stopped, until a later close shows it; the next run's recovery checks the rest. */
    private final Set<DriverSession> unstopped = ConcurrentHashMap.newKeySet();
    /** What closing the agent closes after its sessions: the driver and broker it built. */
    private final List<AutoCloseable> owned;
    private final Executor callbacks;
    private final UnaryOperator<AgentPermissionScope> sandbox;
    /** Why no session can start, when the agent has no CLI to run. */
    private final Optional<String> unavailable;
    /** The release this library is tested with, for a CLI the caller chose that may be newer. */
    private final Optional<String> testedVersion;
    private final Set<DriverSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile Optional<List<Model>> models = Optional.empty();
    /** Whether the CLI was found ready: a driver learns what its CLI supports, such as Claude's steering, only by checking it. */
    private volatile boolean checked;

    /** A gateway's address, and its secret, read as the provider's settings say. */
    public record Gateway(URI baseUrl, Supplier<String> token)
    {
        public Gateway
        {
            requireNonNull(baseUrl, "baseUrl is null");
            requireNonNull(token, "token is null");
        }
    }

    public record CodexSettings(
            Optional<Path> executable,
            Optional<Path> home,
            Optional<Path> stateDirectory,
            Optional<String> account,
            Optional<Gateway> gateway,
            Optional<Supplier<String>> deepSeekKey,
            Optional<Supplier<String>> apiKey,
            Optional<Duration> keepSessions,
            Optional<Executor> callbackExecutor,
            ToolServer toolServer) {}

    public record ClaudeSettings(
            Optional<Path> executable,
            Optional<Path> configDir,
            Optional<Path> stateDirectory,
            Optional<String> account,
            Optional<Gateway> gateway,
            List<String> outsideSandbox,
            List<Path> unixSockets,
            List<String> autoModeContext,
            List<String> autoModeRules,
            boolean reviewSandboxedCommands,
            Optional<Duration> keepSessions,
            Optional<Executor> callbackExecutor,
            ToolServer toolServer) {}

    DriverAgent(AgentDriver driver, String provider, StateFolder state, PermissionBroker broker, Executor callbacks, UnaryOperator<AgentPermissionScope> sandbox)
    {
        this(driver, provider, state, owned(driver, broker), callbacks, sandbox, Optional.empty(), Optional.empty());
    }

    private DriverAgent(AgentDriver driver, String provider, StateFolder state, List<AutoCloseable> owned, Executor callbacks, UnaryOperator<AgentPermissionScope> sandbox,
            Optional<String> unavailable, Optional<String> testedVersion)
    {
        this.unavailable = requireNonNull(unavailable, "unavailable is null");
        this.testedVersion = requireNonNull(testedVersion, "testedVersion is null");
        this.driver = requireNonNull(driver, "driver is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.state = requireNonNull(state, "state is null");
        this.owned = ImmutableList.copyOf(owned);
        this.callbacks = requireNonNull(callbacks, "callbacks is null");
        this.sandbox = requireNonNull(sandbox, "sandbox is null");
    }

    /** Codex's connector owns its broker; the facade still owns its optional transcript sweep. */
    private static List<AutoCloseable> owned(ConnectorDriver driver, Path home, Optional<Duration> keep, SessionSweeps.Pruner pruner)
    {
        return keep.isEmpty() ? ImmutableList.of(driver) : ImmutableList.of(driver, SessionSweeps.keep(home, keep.orElseThrow(), pruner));
    }

    private static List<AutoCloseable> owned(AgentDriver driver, PermissionBroker broker)
    {
        return driver instanceof AutoCloseable closeable ? ImmutableList.of(closeable, broker) : ImmutableList.of(broker);
    }

    public static Agent codex(CodexSettings settings)
    {
        return codex(settings, CodexBinary.standard());
    }

    static Agent codex(CodexSettings settings, CodexBinary binary)
    {
        StateFolder state = StateFolder.open(settings.stateDirectory().orElse(DEFAULT_STATE));
        Path home = settings.home().orElse(state.root().resolve("codex")).toAbsolutePath();
        CodexConnectorConfig configuration = new CodexConnectorConfig(settings.executable(), settings.account(),
                settings.gateway().map(value -> new CodexGateway(value.baseUrl().toString(), () -> Optional.ofNullable(value.token().get()))),
                settings.deepSeekKey(), settings.apiKey());
        CodexConnectorDriver driver = new CodexConnector(binary).create(configuration, new ConnectorContext(home, state.supervisor(), settings.toolServer()));
        return new DriverAgent(driver, "codex", state, owned(driver, home, settings.keepSessions(), CodexHome::pruneSessions),
                settings.callbackExecutor().orElse(VIRTUAL_THREADS), UnaryOperator.identity(), driver.unavailable(), driver.testedVersion());
    }

    public static Agent claude(ClaudeSettings settings)
    {
        StateFolder state = StateFolder.open(settings.stateDirectory().orElse(DEFAULT_STATE));
        Path home = settings.configDir().orElse(state.root().resolve("claude")).toAbsolutePath();
        ConnectorDriver driver = new ClaudeConnector().create(settings, new ConnectorContext(home, state.supervisor(), settings.toolServer()));
        List<String> outside = settings.outsideSandbox().stream().map(program -> program + " *").collect(toImmutableList());
        List<String> sockets = settings.unixSockets().stream().map(Path::toString).collect(toImmutableList());
        UnaryOperator<AgentPermissionScope> sandbox = scope -> {
            AgentPermissionScope reviewed = scope.withReviewer(settings.autoModeContext(), settings.autoModeRules(), settings.reviewSandboxedCommands());
            // These reach past the sandbox, so only a session that may use the network gets them.
            return reviewed.network() ? reviewed.withUnsandboxedCommands(outside).withUnixSockets(sockets) : reviewed;
        };
        return new DriverAgent(driver, "claude", state, ImmutableList.of(driver),
                settings.callbackExecutor().orElse(VIRTUAL_THREADS), sandbox, Optional.empty(), Optional.empty());
    }

    @Override
    public AgentStatus status()
    {
        if (unavailable.isPresent()) {
            return new AgentStatus(AgentStatus.Readiness.CLI_MISSING, Optional.empty(), unavailable.orElseThrow(), Optional.empty(), capabilities(driver.capabilities()));
        }
        try {
            AgentProviderStatus status = driver.probe();
            AgentStatus.Readiness readiness = switch (status.readiness()) {
                case READY, DEGRADED -> AgentStatus.Readiness.READY;
                case AUTHENTICATION_REQUIRED -> AgentStatus.Readiness.SIGN_IN_REQUIRED;
                case CLI_MISSING -> AgentStatus.Readiness.CLI_MISSING;
                case UNSUPPORTED -> AgentStatus.Readiness.UNSUPPORTED_VERSION;
                case TEMPORARILY_UNAVAILABLE -> AgentStatus.Readiness.TEMPORARILY_UNAVAILABLE;
            };
            String detail = status.diagnostic();
            if (readiness == AgentStatus.Readiness.READY && status.version().filter(version -> newer(version, testedVersion)).isPresent()) {
                detail += " This library is tested with Codex " + testedVersion.orElseThrow() + "; " + status.version().orElseThrow() + " is newer and untested.";
            }
            if (readiness == AgentStatus.Readiness.READY) {
                checked = true;
            }
            return new AgentStatus(readiness, status.version(), detail, status.signInCommand(), capabilities(status.capabilities()));
        }
        catch (RuntimeException failure) {
            return new AgentStatus(AgentStatus.Readiness.UNAVAILABLE, Optional.empty(), DriverSession.message(failure), Optional.empty(),
                    capabilities(driver.capabilities()));
        }
    }

    @Override
    public List<Model> models()
    {
        requireAvailable();
        try {
            List<Model> listed = driver.models().stream().map(DriverAgent::model).collect(toImmutableList());
            models = Optional.of(listed);
            return listed;
        }
        catch (RuntimeException failure) {
            throw DriverSession.agentException(failure);
        }
    }

    @Override
    public Limits limits()
    {
        if (unavailable.isPresent()) {
            return new Limits(Optional.empty(), ImmutableList.of(), unavailable);
        }
        try {
            return limits(driver.quota());
        }
        catch (RuntimeException failure) {
            return new Limits(Optional.empty(), ImmutableList.of(), Optional.of(DriverSession.message(failure)));
        }
    }

    @Override
    public Session startSession(SessionOptions options)
    {
        requireChecked();
        return remember(DriverSession.start(this, requireNonNull(options, "options is null"), Optional.empty()));
    }

    @Override
    public Session resumeSession(SessionRef ref, SessionOptions options)
    {
        requireNonNull(options, "options is null");
        requireChecked();
        return remember(DriverSession.start(this, options, Optional.of(DriverSession.reference(driver.id(), provider, requireNonNull(ref, "ref is null")))));
    }

    @Override
    public Set<Path> unconfirmedDirectories()
    {
        return ImmutableSet.<Path>builder()
                .addAll(state.unconfirmedDirectories())
                .addAll(unstopped.stream().map(DriverSession::workingDirectory).iterator())
                .build();
    }

    /** A session of this run whose process could not be shown stopped. */
    void unconfirmed(DriverSession session)
    {
        unstopped.add(session);
    }

    /** A session whose process a later close showed stopped. */
    void stopped(DriverSession session)
    {
        unstopped.remove(session);
    }

    @Override
    public void close()
    {
        Optional<AgentException> failure = Optional.empty();
        for (DriverSession session : ImmutableList.copyOf(sessions)) {
            try {
                session.close();
            }
            catch (RuntimeException closing) {
                failure = failure.or(() -> Optional.of(DriverSession.agentException(closing)));
            }
        }
        for (AutoCloseable resource : owned) {
            try {
                resource.close();
            }
            catch (Exception closing) {
                failure = failure.or(() -> Optional.of(DriverSession.agentException(closing)));
            }
        }
        if (failure.isPresent()) {
            throw failure.orElseThrow();
        }
    }

    AgentDriver driver()
    {
        return driver;
    }

    String provider()
    {
        return provider;
    }

    StateFolder state()
    {
        return state;
    }

    /** Where a finished driver turn is settled: off the driver's thread, since settling takes the session's lock. */
    Executor settling()
    {
        return VIRTUAL_THREADS;
    }

    Executor callbacks()
    {
        return callbacks;
    }

    UnaryOperator<AgentPermissionScope> sandbox()
    {
        return sandbox;
    }

    /** The model sessions use when none is named, once the catalog has been read. */
    Optional<String> defaultModel()
    {
        return models.orElseGet(this::models).stream().filter(Model::isDefault).map(Model::id).findFirst();
    }

    void forget(DriverSession session)
    {
        sessions.remove(session);
    }

    private Session remember(DriverSession session)
    {
        sessions.add(session);
        return session;
    }

    /** The first session waits for a check of the CLI, which tells the driver what the CLI supports. */
    private void requireChecked()
    {
        requireAvailable();
        if (!checked) {
            AgentStatus status = status();
            if (!status.ready()) {
                throw new AgentException(status.detail());
            }
        }
    }

    private void requireAvailable()
    {
        if (unavailable.isPresent()) {
            throw new AgentException(unavailable.orElseThrow());
        }
    }

    /** Whether {@code version}'s release numbers come after {@code tested}'s; a suffix such as {@code -alpha} is ignored. */
    static boolean newer(String version, Optional<String> tested)
    {
        if (tested.isEmpty()) {
            return false;
        }
        int[] reported = release(version);
        int[] pinned = release(tested.orElseThrow());
        return Arrays.compare(reported, pinned) > 0;
    }

    private static int[] release(String version)
    {
        return Arrays.stream(version.split("[^0-9.]", 2)[0].split("\\."))
                .filter(part -> !part.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static Capabilities capabilities(AgentCapabilities capabilities)
    {
        return new Capabilities(capabilities.steering(), capabilities.nestedAgentActivity(), capabilities.attachmentMediaTypes());
    }

    private static Model model(ProviderModel model)
    {
        List<Model.ServiceTier> tiers = model.serviceTiers().stream()
                .map(tier -> new Model.ServiceTier(tier.id(), tier.name(), tier.description()))
                .collect(toImmutableList());
        return new Model(model.id(), model.label(), model.description(), model.efforts(), model.defaultEffort(), model.isDefault(), tiers,
                model.inputModalities().contains("image"));
    }

    private static Limits limits(ProviderQuotaSnapshot quota)
    {
        return new Limits(quota.plan(), quota.windows().stream()
                .map(window -> new Limits.Window(window.id(), window.label(), window.usedPercent(), window.startsAt(), window.resetsAt(),
                        window.amount().map(amount -> new Limits.Amount(amount.used(), amount.total(), amount.currency()))))
                .collect(toImmutableList()), quota.message());
    }
}
