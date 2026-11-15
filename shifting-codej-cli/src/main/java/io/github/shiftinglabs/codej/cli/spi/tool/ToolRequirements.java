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
package io.github.shiftinglabs.codej.cli.spi.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable requested or granted authority. Declarations are not grants and lexical scope checks are not filesystem containment. */
public final class ToolRequirements
{
    private final Set<ToolCapability> capabilities;
    private final List<Path> reads;
    private final List<Path> writes;

    private ToolRequirements(Builder builder)
    {
        checkArgument(builder.capabilities.size() <= 256, "too many capabilities");
        capabilities = Set.copyOf(builder.capabilities);
        reads = List.copyOf(builder.reads);
        writes = List.copyOf(builder.writes);
        checkArgument(!capabilities.contains(ToolCapability.WORKSPACE_READ) || !reads.isEmpty(), "workspace reads require scoped paths");
        checkArgument(!capabilities.contains(ToolCapability.WORKSPACE_WRITE) || !writes.isEmpty(), "workspace writes require scoped paths");
    }

    public static Builder builder() { return new Builder(); }

    public static ToolRequirements none() { return builder().build(); }

    public Set<ToolCapability> capabilities() { return capabilities; }

    public List<Path> reads() { return reads; }

    public List<Path> writes() { return writes; }

    /**
     * Compares explicit scopes, without consulting policy or accessing files. Hosts must reject
     * unsupported capabilities first; backends still enforce actual paths/identities at use.
     * Write authority does not implicitly grant read authority.
     */
    public boolean permits(ToolRequirements requested)
    {
        requireNonNull(requested, "requested is null");
        return capabilities.containsAll(requested.capabilities)
                && covers(reads, requested.reads) && covers(writes, requested.writes);
    }

    private static boolean covers(List<Path> grants, List<Path> requested)
    {
        return requested.stream().allMatch(path -> grants.stream().anyMatch(root -> root.equals(Path.of(".")) || path.startsWith(root)));
    }

    public static final class Builder
    {
        private final Set<ToolCapability> capabilities = new LinkedHashSet<>();
        private final List<Path> reads = new ArrayList<>();
        private final List<Path> writes = new ArrayList<>();

        private Builder() {}

        public Builder read(Path path)
        {
            add(reads, path);
            capabilities.add(ToolCapability.WORKSPACE_READ);
            return this;
        }

        public Builder write(Path path)
        {
            add(writes, path);
            capabilities.add(ToolCapability.WORKSPACE_WRITE);
            return this;
        }

        /** Adds explicit non-filesystem authority; workspace capabilities additionally require scoped paths. */
        public Builder require(ToolCapability capability)
        {
            capabilities.add(requireNonNull(capability, "capability is null"));
            checkArgument(capabilities.size() <= 256, "too many capabilities");
            return this;
        }

        public ToolRequirements build() { return new ToolRequirements(this); }

        private static void add(List<Path> paths, Path path)
        {
            requireNonNull(path, "path is null");
            checkArgument(!path.isAbsolute() && path.toString().length() <= 4096, "expected bounded workspace-relative path");
            for (Path component : path) {
                checkArgument(!component.toString().equals(".."), "parent traversal is forbidden");
            }
            Path normalized = path.normalize();
            if (normalized.toString().isEmpty()) {
                normalized = Path.of(".");
            }
            if (!paths.contains(normalized)) {
                checkArgument(paths.size() < 256, "too many paths");
                paths.add(normalized);
            }
        }
    }
}
