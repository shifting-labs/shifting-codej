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
package io.github.shiftinglabs.codej;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A model an agent can run.
 *
 * @param id what {@link SessionOptions.Builder#model(String)} and {@link TurnOptions#withModel(String)} take
 * @param name the provider's display name
 * @param description the provider's one-line description
 * @param efforts the reasoning efforts it accepts, such as {@code low} or {@code high}; empty when there is no choice
 * @param defaultEffort the effort used when none is given
 * @param isDefault whether sessions use this model when none is given
 * @param serviceTiers the service tiers it accepts, such as a faster priority tier
 * @param acceptsImages whether it reads images sent with a turn
 */
public record Model(String id, String name, String description, List<String> efforts, Optional<String> defaultEffort, boolean isDefault, List<ServiceTier> serviceTiers,
        boolean acceptsImages)
{
    /** Creates a model; copies the lists. */
    public Model
    {
        requireNonNull(id, "id is null");
        requireNonNull(name, "name is null");
        requireNonNull(description, "description is null");
        efforts = ImmutableList.copyOf(efforts);
        requireNonNull(defaultEffort, "defaultEffort is null");
        serviceTiers = ImmutableList.copyOf(serviceTiers);
    }

    /**
     * A service tier a model accepts.
     *
     * @param id what {@link TurnOptions#withServiceTier(String)} takes
     * @param name the provider's display name, such as {@code Fast}
     * @param description the provider's one-line description
     */
    public record ServiceTier(String id, String name, String description)
    {
        /** Creates a service tier. */
        public ServiceTier
        {
            requireNonNull(id, "id is null");
            requireNonNull(name, "name is null");
            requireNonNull(description, "description is null");
        }
    }
}
