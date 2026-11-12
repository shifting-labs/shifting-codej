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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderServiceTier;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexModelCatalog
{
    private final CodexProtocolCodec codec = new CodexProtocolCodec(new ObjectMapper());

    @Test
    void mapsVisibleModelsInOrderAndPreservesAbsentFieldDefaults()
    {
        CodexModelCatalog catalog = catalog("""
                {"data":[
                  {"id":"hidden","displayName":"Hidden","description":"h","hidden":true,"isDefault":true,
                   "defaultReasoningEffort":"low","supportedReasoningEfforts":[]},
                  {"id":"default","displayName":"Default","description":"d","hidden":false,"isDefault":true,
                   "defaultReasoningEffort":"low","supportedReasoningEfforts":[{"reasoningEffort":"low"}]},
                  {"id":"text","displayName":"Text","description":"t","hidden":false,"isDefault":false,
                   "defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}],
                   "multiAgentVersion":"v2","inputModalities":["text"],
                   "serviceTiers":[{"id":"priority","name":"Fast","description":"Faster processing"}]}]}
                """);

        assertThat(catalog.models()).containsExactly(
                new ProviderModel("default", "Default", "d", ImmutableList.of("low"), Optional.of("low"), true,
                        ImmutableList.of("text", "image"), ImmutableList.of()),
                new ProviderModel("text", "Text", "t", ImmutableList.of("high"), Optional.of("high"), false, ImmutableList.of("text"),
                        ImmutableList.of(new ProviderServiceTier("priority", "Fast", "Faster processing"))));
        assertThat(catalog.select(Optional.empty()))
                .isEqualTo(new CodexModelCatalog.ModelProfile("default", CodexLaunchProfile.ChildProfile.NONE, ImmutableList.of()));
        assertThat(catalog.select(Optional.of("text")))
                .isEqualTo(new CodexModelCatalog.ModelProfile("text", CodexLaunchProfile.ChildProfile.V2,
                        ImmutableList.of(new CodexProtocol.ModelServiceTier("priority", "Fast", "Faster processing"))));
        assertThatThrownBy(() -> catalog.select(Optional.of("hidden")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessage("Codex model hidden is not in the current model catalog");
    }

    @Test
    void deepSeekRequiresExplicitDisabledAgentsAndTextInputInCatalogOrder()
    {
        String models = """
                {"data":[
                  {"id":"deepseek-flash","displayName":"Flash","description":"d","hidden":false,"isDefault":true,
                   "defaultReasoningEffort":"high","supportedReasoningEfforts":[],"multiAgentVersion":"disabled","inputModalities":["text"]},
                  {"id":"deepseek-v4-pro","displayName":"Pro","description":"d","hidden":false,"isDefault":false,
                   "defaultReasoningEffort":"high","supportedReasoningEfforts":[],"multiAgentVersion":"disabled","inputModalities":["text"]}]}
                """;
        assertThatCode(() -> catalog(models).verifyDeepSeek()).doesNotThrowAnyException();

        for (String invalid : ImmutableList.of(
                models.replace("\"multiAgentVersion\":\"disabled\",", ""),
                models.replace(",\"inputModalities\":[\"text\"]", ""),
                models.replace("\"hidden\":false", "\"hidden\":true"),
                models.replace("\"inputModalities\":[\"text\"]", "\"inputModalities\":[\"text\",\"image\"]"),
                models.replace("\"inputModalities\":[\"text\"]",
                        "\"inputModalities\":[\"text\"],\"serviceTiers\":[{\"id\":\"priority\",\"name\":\"Fast\",\"description\":\"Faster processing\"}]"))) {
            assertThatThrownBy(() -> catalog(invalid).verifyDeepSeek())
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessage("DeepSeek model catalog does not match the SDK's attended text profile");
        }
        CodexModelCatalog valid = catalog(models);
        CodexModelCatalog reversed = new CodexModelCatalog(valid.entries().reversed());
        assertThatThrownBy(reversed::verifyDeepSeek).isInstanceOf(AgentUnavailableException.class);
    }

    private CodexModelCatalog catalog(String json)
    {
        return new CodexModelCatalog(codec.decode(json, CodexProtocol.ModelListResponse.class).data());
    }
}
