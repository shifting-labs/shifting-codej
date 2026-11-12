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

import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ApprovalHandler;
import io.github.shiftinglabs.codej.Claude;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.cli.driver.*;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

// Compiled only against 0.164.0-1, then executed unchanged with each jar in a fresh JVM.
public final class LegacyConsumer
{
    private LegacyConsumer() {}

    public static void main(String[] args) throws Exception
    {
        if (args[0].equals("surface")) {
            for (String name : Arrays.copyOfRange(args, 1, args.length)) {
                Class<?> type = Class.forName(name);
                System.out.println(name + " sealed=" + type.isSealed() + " permits=" + (type.isSealed()
                        ? Arrays.toString(Arrays.stream(type.getPermittedSubclasses()).map(Class::getName).sorted().toArray()) : "[]"));
                if (type.isRecord()) {
                    System.out.println(name + " record " + Arrays.toString(type.getRecordComponents()));
                }
                if (type.isEnum()) {
                    System.out.println(name + " enum " + Arrays.toString(Arrays.stream(type.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toArray()));
                }
                // Reflection avoids javac's inlining of old primitive/String constants.
                for (var field : type.getFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() != ApprovalHandler.class) {
                        System.out.println(type.getName() + "." + field.getName() + "=" + field.get(null));
                    }
                }
            }
            System.out.println("ApprovalHandler.DENY_ALL=" + ApprovalHandler.DENY_ALL.decide(null).toCompletableFuture().join());
            return;
        }

        Path state = Path.of(args[1]);
        String version = (String) Codex.class.getField("VERSION").get(null);
        check(args[2].matches(Pattern.quote(version) + "-[1-9][0-9]*(-rc[1-9][0-9]*)?(-SNAPSHOT)?"), "release convention");
        Properties checksums = new Properties();
        try (InputStream stream = Class.forName("io.github.shiftinglabs.codej.sdk.impl.CodexBinary").getResourceAsStream("codex-checksums.properties")) {
            check(stream != null, "relative Codex checksum lookup");
            checksums.load(stream);
        }
        check(checksums.getProperty("osx-aarch_64/archive").startsWith("sha512-"), "archive integrity");
        for (String key : List.of("codex-package.json", "bin/codex", "bin/codex-code-mode-host", "codex-path/rg")) {
            check(checksums.getProperty("osx-aarch_64/" + key).matches("[0-9a-f]{64}"), "file checksum: " + key);
        }
        for (String provider : List.of("codex", "claude")) {
            for (int contract : List.of(1, 3, Integer.MAX_VALUE)) {
                String nativeValue = "thread:with:colons/会話";
                String text = provider + ":" + contract + ":" + nativeValue;
                SessionRef ref = SessionRef.parse(text);
                check(ref.asString().equals(text) && ref.provider().equals(provider), "exact persisted reference");
                int separator = ref.id().indexOf(':');
                check(Integer.parseInt(ref.id().substring(0, separator)) == contract
                        && ref.id().substring(separator + 1).equals(nativeValue), "Bytequay numeric reference parsing");
            }
        }

        AgentDriver driver = new OldDriver();
        check(driver.probe().available() && driver.probe(Optional.empty()).available(), "legacy probe defaults");
        check(!driver.reviewChildAgents() && !driver.reviewChildAgents(Optional.empty(), Optional.empty())
                && driver.models().isEmpty() && driver.label().equals("old-external"), "legacy driver defaults");
        AgentSessionContext context = new AgentSessionContext("old-task", state, state, state.resolve("provider"), AgentPermissionPolicy.AUTO,
                Optional.empty(), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                Optional.empty(), Optional.empty(), SessionAccess.FULL, "");
        try (AgentSession session = driver.open(context, event -> {})) {
            check(!session.retained() && session.resumable() && !session.prepareTurn(context, event -> {}), "legacy session defaults");
            session.allowReuse();
            session.preventReuse();
            RunningTurn turn = session.start(new AgentTurnCommand("old-turn", "hello", List.of(), Duration.ofSeconds(1)));
            check(turn.completion().toCompletableFuture().join().state() == AgentTurnResult.State.COMPLETED && turn.setup().isEmpty(), "legacy turn completion/default");
            turn.cancel();
        }

        check(ApprovalHandler.DENY_ALL.decide(null).toCompletableFuture().join().equals(Decision.DENY), "deny-all constant");
        check(ToolServer.DEFAULT.name().equals("codej"), "default tool server");
        // Explicit missing executables exercise shaded construction/close without probing or launching a provider.
        try (Agent codex = Codex.builder().executable(state.resolve("absent-codex")).home(state.resolve("codex-home"))
                .stateDirectory(state.resolve("codex-state")).apiKey(() -> "fixture-key").build();
                Agent claude = Claude.builder().executable(state.resolve("absent-claude")).stateDirectory(state.resolve("claude-state"))
                        .runOutsideSandbox("mvn", "./mvnw").allowUnixSocket(state.resolve("fixture.sock")).autoModeRules("No push").build()) {
            check(codex != null && claude != null, "shaded builders");
        }
        System.out.println("PASS old compiled public API + legacy driver/session/turn, numeric references, shaded builders/close, checksum lookup and version convention");
    }

    private static void check(boolean passed, String message)
    {
        if (!passed) {
            throw new AssertionError(message);
        }
    }

    private static final class OldDriver implements AgentDriver
    {
        private final AgentLaneId lane = new AgentLaneId("old-external");

        public AgentLaneId id() { return lane; }

        public AgentCapabilities capabilities() { return AgentCapabilities.none(); }

        public AgentProviderStatus probe()
        {
            return new AgentProviderStatus(lane, AgentProviderStatus.Readiness.READY, Optional.empty(), "ready", capabilities());
        }

        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            return new AgentSession()
            {
                public AgentSessionRef reference() { return new AgentSessionRef(lane, 3, "old-session"); }

                public RunningTurn start(AgentTurnCommand command)
                {
                    return new RunningTurn()
                    {
                        public ProviderTurnRef reference() { return new ProviderTurnRef(command.turnId()); }

                        public CompletionStage<AgentTurnResult> completion()
                        {
                            return CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.COMPLETED,
                                    Optional.of("ok"), Optional.empty(), Duration.ZERO, true));
                        }

                        public void cancel() {}
                    };
                }

                public void close() {}
            };
        }
    }
}
