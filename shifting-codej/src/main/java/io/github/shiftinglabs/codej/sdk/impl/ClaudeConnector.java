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
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.claude.ClaudeCliDriver;
import io.github.shiftinglabs.codej.cli.claude.ClaudeGateway;
import io.github.shiftinglabs.codej.cli.claude.ClaudeHome;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Builds the Claude Code runtime using the existing builder settings and host-selected profile. */
public final class ClaudeConnector
        implements AgentConnector<DriverAgent.ClaudeSettings>
{
    @Override
    public String id()
    {
        return "claude-code";
    }

    @Override
    public Class<DriverAgent.ClaudeSettings> configurationType()
    {
        return DriverAgent.ClaudeSettings.class;
    }

    @Override
    public ConnectorDriver create(DriverAgent.ClaudeSettings settings, ConnectorContext context)
    {
        requireNonNull(settings, "settings is null");
        requireNonNull(context, "context is null");
        Path home = context.profileDirectory();
        String executable = settings.executable().map(Path::toString).orElse("claude");
        // Claude's gateway token has always been resolved at build time, once per driver.
        Optional<ClaudeGateway> gateway = settings.gateway().map(value -> new ClaudeGateway(value.baseUrl().toString(), value.token().get()));
        String profile = Hashing.sha256().hashString(home.toString(), StandardCharsets.UTF_8).toString().substring(0, 12);
        AgentLaneId lane = settings.account().isPresent() || gateway.isPresent()
                ? new AgentLaneId(AgentLaneId.CLAUDE_ACCOUNT_PREFIX + profile)
                : AgentLaneId.CLAUDE_CLI;
        String label = settings.account().map(email -> "Claude · " + email).orElse(gateway.isPresent() ? "Claude gateway" : "Claude Code");
        ObjectMapper mapper = new ObjectMapper();
        PermissionBroker broker = new PermissionBroker(mapper, context.toolServer());
        try {
            ClaudeCliDriver driver = new ClaudeCliDriver(executable, mapper, broker, context.processSupervisor(),
                    ClaudeHome.prepare(home), lane, label, settings.account(), gateway);
            try {
                ImmutableList.Builder<AutoCloseable> owned = ImmutableList.<AutoCloseable>builder().add(driver, broker);
                settings.keepSessions().ifPresent(keep -> owned.add(SessionSweeps.keep(home, keep, ClaudeHome::pruneSessions)));
                return new ClaudeConnectorDriver(driver, new ConnectorIdentity(id(), "profile-" + profile), owned.build());
            }
            catch (RuntimeException | Error failure) {
                closeAfterFailure(driver, failure);
                throw failure;
            }
        }
        catch (IOException failure) {
            closeAfterFailure(broker, failure);
            throw new AgentException("Claude's config folder could not be prepared: " + home, failure);
        }
        catch (RuntimeException | Error failure) {
            closeAfterFailure(broker, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure)
    {
        try {
            resource.close();
        }
        catch (Exception closing) {
            failure.addSuppressed(closing);
        }
    }
}
