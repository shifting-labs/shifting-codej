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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Opt-in built-in Linux command isolation. No Docker, downloads, bundled native code or new Java dependencies. */
public final class BuiltinCommands
{
    private BuiltinCommands() {}

    /**
     * Uses an existing Linux systemd user manager, cgroup v2 and private namespaces. The host must
     * support systemd 257+ isolation features; actual kernel quotas and namespaces are verified
     * while a trusted Java launcher waits behind a gate. Unsupported hosts fail without running
     * the requested command. Other platforms retain their existing single-process shell behavior.
     *
     * @param limits hard per-command limits
     * @return explicit configuration for CommandToolProvider, requiring a shared execution budget
     */
    public static CommandToolProvider.Configuration configuration(CommandLimits limits)
    {
        return configuration(limits, CodingTools.defaults());
    }

    /**
     * Additionally bounds workspace export by the supplied file, search-byte and entry limits.
     * The workspace is copied using contained no-follow reads; protected paths and links are omitted.
     *
     * @param limits hard per-command limits
     * @param workspaceLimits snapshot limits; command/output fields do not override limits
     * @return explicit session-owned backend configuration
     */
    public static CommandToolProvider.Configuration configuration(CommandLimits limits, CodingTools workspaceLimits)
    {
        requireNonNull(limits, "limits is null");
        requireNonNull(workspaceLimits, "workspaceLimits is null");
        return new CommandToolProvider.Configuration(limits, (selected, context) -> open(selected, workspaceLimits, context));
    }

    private static CommandToolProvider.Backend open(CommandLimits limits, CodingTools workspaceLimits, ToolSetContext context)
    {
        requireNonNull(context, "context is null");
        try {
            if (!System.getProperty("os.name").equals("Linux") || !Files.isExecutable(Path.of("/usr/bin/systemd-run"))
                    || !Files.isExecutable(Path.of("/usr/bin/systemctl")) || !Files.isRegularFile(Path.of("/sys/fs/cgroup/cgroup.controllers"))) {
                throw new IOException("Linux systemd user services with cgroup v2 and namespace support are required");
            }
            String boot = NativeUnit.read(Path.of("/proc/sys/kernel/random/boot_id"), 128).strip();
            var service = new SystemdCommandService(context.connector().processSupervisor(), context.shutdownTimeout());
            return new NativeCommandBackend(limits, workspaceLimits, context, service, boot);
        }
        catch (IOException failure) {
            throw new IllegalStateException("Built-in command trees require Linux systemd 257+, a user manager, cgroup v2 and namespace support");
        }
    }

    static void artifacts(NativeCommandRegistry.State state, Path worktree, List<Path> excluded, CodingTools limits,
            io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext context)
            throws IOException
    {
        Path root = state.root();
        Files.createDirectory(root);
        for (String path : List.of("workspace", "tmp", "proc", "sys", "dev", "run/codej", "etc")) {
            Files.createDirectories(root.resolve(path));
        }
        var exclusions = new ArrayList<>(excluded);
        exclusions.add(Path.of(state.directory()));
        try (var source = WorkspaceFiles.snapshotSource(worktree, limits, exclusions)) {
            source.snapshot(root.resolve("workspace"), context);
        }
        Path helper = Path.of(state.directory()).resolve("launcher.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(helper, java.nio.file.StandardOpenOption.CREATE_NEW))) {
            for (Class<?> type : List.of(NativeCommandLauncher.class, NativeCommandLauncher.Request.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new java.util.jar.JarEntry(resource));
                try (var input = requireNonNull(type.getResourceAsStream("/" + resource), "launcher resource missing")) {
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
        }
    }
}
