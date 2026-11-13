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
package io.github.shiftinglabs.codej.tool;

import java.util.Optional;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable logical identity; the builder permits optional additions without changing a record shape. */
public final class ToolDescriptor
{
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private final String name;
    private final String description;
    private final int contractVersion;
    private final Optional<String> implementationRevision;

    private ToolDescriptor(Builder builder)
    {
        name = builder.name;
        description = builder.description;
        contractVersion = builder.contractVersion;
        implementationRevision = builder.implementationRevision;
    }

    /**
     * Creates a descriptor builder; contract version defaults to one.
     *
     * @param name stable model-facing name, following the existing Tool name rules
     * @param description human-readable tool behavior, at most 8192 characters
     * @return the builder
     */
    public static Builder builder(String name, String description) { return new Builder(name, description); }

    /**
     * Returns the stable model-facing name.
     *
     * @return the name
     */
    public String name() { return name; }

    /**
     * Returns the advertised description.
     *
     * @return the description
     */
    public String description() { return description; }

    /**
     * Returns the logical behavior version, independent of implementation revisions.
     *
     * @return a positive version
     */
    public int contractVersion() { return contractVersion; }

    /**
     * Returns optional diagnostic implementation metadata. This does not authorize replay or prove compatibility.
     *
     * @return the revision
     */
    public Optional<String> implementationRevision() { return implementationRevision; }

    /** Mutable construction settings; the resulting descriptor is immutable. */
    public static final class Builder
    {
        private final String name;
        private final String description;
        private int contractVersion = 1;
        private Optional<String> implementationRevision = Optional.empty();

        private Builder(String name, String description)
        {
            this.name = requireNonNull(name, "name is null");
            checkArgument(NAME.matcher(name).matches(), "invalid tool name");
            this.description = requireNonNull(description, "description is null");
            checkArgument(!description.isBlank() && description.length() <= 8192, "invalid tool description");
        }

        /**
         * Sets the version of the logical contract, including behavioral changes not visible in a schema.
         *
         * @param value a positive version
         * @return this builder
         */
        public Builder contractVersion(int value)
        {
            checkArgument(value > 0, "contract version must be positive");
            contractVersion = value;
            return this;
        }

        /**
         * Sets optional implementation metadata without changing the logical contract.
         *
         * @param value a nonblank revision of at most 128 characters
         * @return this builder
         */
        public Builder implementationRevision(String value)
        {
            requireNonNull(value, "revision is null");
            checkArgument(!value.isBlank() && value.length() <= 128, "invalid implementation revision");
            implementationRevision = Optional.of(value);
            return this;
        }

        /**
         * Captures these settings.
         *
         * @return an immutable descriptor
         */
        public ToolDescriptor build() { return new ToolDescriptor(this); }
    }
}
