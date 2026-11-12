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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared adapter contract; all provider responses are in memory and no native runtime is launched. */
public abstract class AbstractTestConnectorDriver
{
    protected abstract ConnectorDriver connector(AgentDriver delegate);

    @Test
    protected void preservesProviderMetadataAndOptionalCatalogAndQuota()
    {
        QueryDriver provider = new QueryDriver();
        try (ConnectorDriver driver = connector(provider)) {
            assertThat(driver.id()).isEqualTo(provider.id());
            assertThat(driver.label()).isEqualTo("Custom runtime label");
            assertThat(driver.identity()).isEqualTo(driver.identity());
            assertThat(driver.capabilities()).isSameAs(provider.capabilities());
            assertThat(driver.probe()).isSameAs(provider.status);
            assertThat(driver.models()).isSameAs(provider.catalog);
            assertThat(driver.quota()).isSameAs(provider.limits);
            assertThat(driver.reviewChildAgents()).isFalse();
        }
    }

    @Test
    protected void preservesModelAndResumeArgumentsInsteadOfUsingDefaultOverloads()
    {
        QueryDriver provider = new QueryDriver();
        Optional<String> model = Optional.of("custom-model");
        Optional<AgentSessionRef> reference = Optional.of(new AgentSessionRef(provider.id(), 1, "saved:conversation"));
        try (ConnectorDriver driver = connector(provider)) {
            assertThat(driver.probe(model)).isSameAs(provider.selectedStatus);
            assertThat(provider.probedModel).isEqualTo(model);
            assertThat(driver.probe(Optional.empty())).isSameAs(provider.selectedStatus);
            assertThat(provider.probedModel).isEmpty();
            assertThat(driver.reviewChildAgents(reference, model)).isTrue();
            assertThat(provider.reviewReference).isEqualTo(reference);
            assertThat(provider.reviewModel).isEqualTo(model);
            assertThat(driver.reviewChildAgents(Optional.empty(), Optional.empty())).isFalse();
            assertThat(provider.reviewReference).isEmpty();
            assertThat(provider.reviewModel).isEmpty();
        }
    }

    @Test
    protected void preservesProviderFailuresWithoutConvertingThemToEmptyResults()
    {
        QueryDriver provider = new QueryDriver();
        provider.failure = Optional.of(new AgentUnavailableException("Provider unavailable"));
        try (ConnectorDriver driver = connector(provider)) {
            assertThatThrownBy(driver::models).isSameAs(provider.failure.orElseThrow());
            assertThatThrownBy(driver::quota).isSameAs(provider.failure.orElseThrow());
            assertThatThrownBy(driver::probe).isSameAs(provider.failure.orElseThrow());
            assertThatThrownBy(() -> driver.probe(Optional.of("custom-model"))).isSameAs(provider.failure.orElseThrow());
        }
    }

    private static final class QueryDriver
            implements AgentDriver
    {
        private final AgentCapabilities features = AgentCapabilities.none();
        private final AgentProviderStatus status = status("Default probe");
        private final AgentProviderStatus selectedStatus = status("Model-specific probe");
        private final List<ProviderModel> catalog = List.of(new ProviderModel("custom-model", "Custom", "", List.of(), Optional.empty(), true,
                List.of("text"), List.of()));
        private final ProviderQuotaSnapshot limits = new ProviderQuotaSnapshot(Optional.of("Custom plan"), List.of(), Optional.of("Quota snapshot"));
        private Optional<String> probedModel = Optional.empty();
        private Optional<AgentSessionRef> reviewReference = Optional.empty();
        private Optional<String> reviewModel = Optional.empty();
        private Optional<RuntimeException> failure = Optional.empty();

        @Override
        public AgentLaneId id() { return new AgentLaneId("contract-lane"); }

        @Override
        public String label() { return "Custom runtime label"; }

        @Override
        public AgentCapabilities capabilities() { return features; }

        @Override
        public AgentProviderStatus probe()
        {
            checkAvailable();
            return status;
        }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            checkAvailable();
            probedModel = model;
            return selectedStatus;
        }

        @Override
        public List<ProviderModel> models()
        {
            checkAvailable();
            return catalog;
        }

        @Override
        public ProviderQuotaSnapshot quota()
        {
            checkAvailable();
            return limits;
        }

        @Override
        public boolean reviewChildAgents(Optional<AgentSessionRef> reference, Optional<String> model)
        {
            reviewReference = reference;
            reviewModel = model;
            return reference.isPresent() && model.isPresent();
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            throw new AssertionError("Metadata queries must not open a session");
        }

        private AgentProviderStatus status(String diagnostic)
        {
            return new AgentProviderStatus(id(), AgentProviderStatus.Readiness.READY, Optional.empty(), diagnostic, capabilities());
        }

        private void checkAvailable()
        {
            failure.ifPresent(unavailable -> { throw unavailable; });
        }
    }
}
