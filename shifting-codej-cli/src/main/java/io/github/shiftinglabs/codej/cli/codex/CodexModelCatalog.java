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
package io.github.shiftinglabs.codej.cli.codex;

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderServiceTier;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The catalog's visible models and the launch profile selected for a session. */
record CodexModelCatalog(List<CodexProtocol.ModelEntry> entries)
{
    CodexModelCatalog
    {
        entries = ImmutableList.copyOf(entries);
    }

    List<ProviderModel> models()
    {
        return entries.stream()
                .filter(entry -> !entry.hidden())
                .map(entry -> new ProviderModel(
                        entry.id(),
                        entry.displayName(),
                        entry.description(),
                        entry.supportedReasoningEfforts().stream()
                                .map(CodexProtocol.ReasoningEffortOption::reasoningEffort)
                                .collect(toImmutableList()),
                        Optional.of(entry.defaultReasoningEffort()),
                        entry.isDefault(),
                        // The schema's default when the field is absent: text and image.
                        entry.inputModalities().orElse(ImmutableList.of("text", "image")),
                        entry.serviceTiers().orElse(ImmutableList.of()).stream()
                                .map(tier -> new ProviderServiceTier(tier.id(), tier.name(), tier.description()))
                                .collect(toImmutableList())))
                .collect(toImmutableList());
    }

    ModelProfile select(Optional<String> selectedModel)
    {
        CodexProtocol.ModelEntry selected = entries.stream()
                .filter(entry -> !entry.hidden())
                .filter(entry -> selectedModel.map(entry.id()::equals).orElse(entry.isDefault()))
                .findFirst()
                .orElseThrow(() -> new AgentUnavailableException(selectedModel
                        .map(model -> "Codex model " + model + " is not in the current model catalog")
                        .orElse("Codex model catalog has no visible default model")));
        CodexProtocol.MultiAgentVersion version = selected.multiAgentVersion().orElse(CodexProtocol.MultiAgentVersion.DISABLED);
        CodexLaunchProfile.ChildProfile childProfile = switch (version) {
            case DISABLED -> CodexLaunchProfile.ChildProfile.NONE;
            case V1 -> CodexLaunchProfile.ChildProfile.V1;
            case V2 -> CodexLaunchProfile.ChildProfile.V2;
            case UNKNOWN -> throw new AgentUnavailableException("Codex model " + selected.id() + " advertises an unknown multi-agent version");
        };
        return new ModelProfile(selected.id(), childProfile, selected.serviceTiers().orElse(ImmutableList.of()));
    }

    void verifyDeepSeek()
    {
        List<String> ids = entries.stream().map(CodexProtocol.ModelEntry::id).collect(toImmutableList());
        if (!ids.equals(ImmutableList.of("deepseek-flash", "deepseek-v4-pro"))
                || entries.stream().anyMatch(entry -> entry.hidden()
                        || entry.multiAgentVersion().orElse(CodexProtocol.MultiAgentVersion.UNKNOWN) != CodexProtocol.MultiAgentVersion.DISABLED
                        || !entry.inputModalities().orElse(ImmutableList.of()).equals(ImmutableList.of("text"))
                        || !entry.serviceTiers().orElse(ImmutableList.of()).isEmpty())) {
            throw new AgentUnavailableException("DeepSeek model catalog does not match the SDK's attended text profile");
        }
    }

    record ModelProfile(String id, CodexLaunchProfile.ChildProfile childProfile, List<CodexProtocol.ModelServiceTier> serviceTiers)
    {
        ModelProfile
        {
            requireNonNull(id, "id is null");
            requireNonNull(childProfile, "childProfile is null");
            serviceTiers = ImmutableList.copyOf(serviceTiers);
        }
    }
}
