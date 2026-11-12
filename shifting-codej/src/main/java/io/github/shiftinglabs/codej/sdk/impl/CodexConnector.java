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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.cli.codex.CodexCliDriver;
import io.github.shiftinglabs.codej.cli.codex.CodexHome;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Constructs the existing Codex runtime and its owned MCP broker without starting a provider process. */
public final class CodexConnector
        implements AgentConnector<CodexConnectorConfig>
{
    private final CodexBinary binary;

    public CodexConnector()
    {
        this(CodexBinary.standard());
    }

    CodexConnector(CodexBinary binary)
    {
        this.binary = requireNonNull(binary, "binary is null");
    }

    @Override
    public String id()
    {
        return "codex";
    }

    @Override
    public Class<CodexConnectorConfig> configurationType()
    {
        return CodexConnectorConfig.class;
    }

    @Override
    public CodexConnectorDriver create(CodexConnectorConfig configuration, ConnectorContext context)
    {
        requireNonNull(configuration, "configuration is null");
        requireNonNull(context, "context is null");
        Path home = context.profileDirectory();
        Optional<String> unavailable = Optional.empty();
        Optional<Path> chosen = configuration.executable();
        if (chosen.isEmpty()) {
            try {
                chosen = Optional.of(binary.install());
            }
            catch (AgentException failure) {
                unavailable = Optional.of(failure.getMessage());
            }
        }
        String executable = chosen.map(Path::toString).orElse("codex");
        ObjectMapper mapper = new ObjectMapper();
        PermissionBroker broker = new PermissionBroker(mapper, context.toolServer());
        String profile = Hashing.sha256().hashString(home.toString(), StandardCharsets.UTF_8).toString().substring(0, 12);
        CodexCliDriver driver;
        try {
            if (configuration.deepSeekKey().isPresent()) {
                Supplier<String> key = configuration.deepSeekKey().orElseThrow();
                driver = CodexCliDriver.deepSeek(executable, Codex.VERSION, mapper, context.processSupervisor(), home, broker,
                        new AgentLaneId("deepseek-key-" + profile), "DeepSeek", () -> Optional.ofNullable(key.get()));
            }
            else if (configuration.gateway().isPresent()) {
                driver = CodexCliDriver.gateway(executable, Codex.VERSION, mapper, context.processSupervisor(), CodexHome.prepare(home), broker,
                        new AgentLaneId(AgentLaneId.CODEX_ACCOUNT_PREFIX + profile), "Codex gateway", configuration.gateway().orElseThrow());
            }
            else if (configuration.apiKey().isPresent()) {
                Supplier<String> key = configuration.apiKey().orElseThrow();
                driver = CodexCliDriver.apiKey(executable, Codex.VERSION, mapper, context.processSupervisor(), CodexHome.prepare(home), broker,
                        new AgentLaneId(AgentLaneId.CODEX_ACCOUNT_PREFIX + profile), "Codex · OpenAI API key", () -> Optional.ofNullable(key.get()));
            }
            else if (configuration.account().isPresent()) {
                String email = configuration.account().orElseThrow();
                driver = CodexCliDriver.account(executable, Codex.VERSION, mapper, context.processSupervisor(), CodexHome.prepare(home), broker,
                        new AgentLaneId(AgentLaneId.CODEX_ACCOUNT_PREFIX + profile), "Codex · " + email, email);
            }
            else {
                driver = new CodexCliDriver(executable, Codex.VERSION, mapper, context.processSupervisor(), CodexHome.prepare(home), broker);
            }
        }
        catch (IOException failure) {
            AgentException reported = new AgentException("Codex's home could not be prepared: " + home, failure);
            closeAfterFailure(broker, reported);
            throw reported;
        }
        catch (RuntimeException | Error failure) {
            closeAfterFailure(broker, failure);
            throw failure;
        }
        return new CodexConnectorDriver(driver, broker, new ConnectorIdentity(id(), "home-" + profile), unavailable,
                configuration.executable().map(_ -> Codex.VERSION));
    }

    private static void closeAfterFailure(PermissionBroker broker, Throwable failure)
    {
        try {
            broker.close();
        }
        catch (RuntimeException closing) {
            failure.addSuppressed(closing);
        }
    }
}
