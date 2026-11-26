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
import com.google.common.collect.Lists;
import io.github.shiftinglabs.codej.cli.api.tools.FakeCodingSupervisor;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionOpenException;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestAntigravitySession
{
    @TempDir
    private Path directory;

    @Test
    void protocolRecordsCheckReferencesAndCopyCollections()
    {
        var model = new AcpProtocol.ModelOption("gemini");
        var models = Lists.newArrayList(model);
        var catalog = new AcpProtocol.Models(models);
        models.clear();

        assertThat(catalog.availableModels()).isInstanceOf(ImmutableList.class).containsExactly(model);
        assertThrows(NullPointerException.class, () -> new AcpProtocol.ModelOption(null));
        assertThrows(NullPointerException.class, () -> new AcpProtocol.Models(null));
        assertThrows(NullPointerException.class, () -> new AcpProtocol.ConfigOption("model", null));
        assertThat(new AcpProtocol.ConfigOption("model", Optional.empty()).category()).isEmpty();
    }

    @Test
    void missingInitializeReplyClosesAndAcknowledgesLaunch()
    {
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        supervisor.pendingExit = new CompletableFuture<>();

        AgentUnavailableException failure = assertThrows(AgentUnavailableException.class, () -> open(supervisor, () -> {}));

        assertThat(failure).hasMessageContaining("Timed out");
        assertThat(supervisor.stopped).containsExactly("launch-1");
        assertThat(supervisor.acknowledged).isEmpty();
        supervisor.pendingExit.complete(new ProcessExit(0, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
        assertThat(supervisor.acknowledged).containsExactly("launch-1");
        assertThat(supervisor.closed).isFalse();
    }

    @Test
    void unverifiableOpenRetainsCleanupUntilChildIsStopped()
    {
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        supervisor.verified = false;
        AtomicInteger released = new AtomicInteger();

        AgentSessionOpenException failure = assertThrows(AgentSessionOpenException.class, () -> open(supervisor, released::incrementAndGet));

        assertThat(supervisor.acknowledged).isEmpty();
        assertThat(released).hasValue(0);
        supervisor.verified = true;
        failure.retryCleanup();
        failure.retryCleanup();
        assertThat(released).hasValue(1);
        assertThat(supervisor.acknowledged).containsExactly("launch-1");
    }

    private void open(FakeCodingSupervisor supervisor, Runnable onClose)
    {
        ProcessSpec spec = new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.DISCUSSION, "task"), List.of("agy", "acp"), directory, Map.of(),
                OptionalInt.empty(), 1024, Duration.ZERO, Duration.ZERO);
        AgentSessionContext context = new AgentSessionContext("task", directory, directory, directory, AgentPermissionPolicy.AUTO, Optional.empty(),
                (_, _) -> { throw new AssertionError(); }, Optional.empty(), Optional.empty(), SessionAccess.READ_ONLY, "");
        AntigravitySession.open(supervisor, () -> spec, new PermissionBroker(new ObjectMapper()), context, _ -> {}, Duration.ofMillis(20), _ -> {}, onClose);
    }
}
