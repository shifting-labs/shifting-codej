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

import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/** Uses only the host's existing user-service utilities; unit state and kernel observations fence the gate. */
final class SystemdCommandService
        implements NativeCommandBackend.Service
{
    private static final String PROPERTIES = "LoadState,ActiveState,Description,RootDirectory,ControlGroup,MainPID";
    private final ProcessSupervisor supervisor;
    private final Duration timeout;
    private final Map<String, String> environment;
    private final Path javaHome;
    private final Map<String, ManagedProcess> controls = new java.util.concurrent.ConcurrentHashMap<>();

    SystemdCommandService(ProcessSupervisor supervisor, Duration timeout)
            throws IOException
    {
        this.supervisor = supervisor;
        this.timeout = timeout;
        Map<String, String> status = new java.util.HashMap<>();
        for (String line : NativeUnit.read(Path.of("/proc/self/status"), 65536).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                status.put(line.substring(0, colon), line.substring(colon + 1).strip());
            }
        }
        String uid = status.getOrDefault("Uid", "").split("\\s+")[0];
        if (!uid.matches("[1-9][0-9]{0,8}")) {
            throw new IOException("An unprivileged user manager is required");
        }
        Path runtime = Path.of("/run/user", uid);
        if (!Files.isDirectory(runtime) || !Files.exists(runtime.resolve("bus"))
                || ((Number) Files.getAttribute(runtime, "unix:uid")).longValue() != Long.parseLong(uid)) {
            throw new IOException("The existing user service manager is unavailable");
        }
        javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        safe(javaHome);
        environment = Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "LC_ALL", "C", "XDG_RUNTIME_DIR", runtime.toString(),
                "DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime.resolve("bus"), "SYSTEMD_PAGER", "cat", "SYSTEMD_COLORS", "0");
    }

    @Override
    public PreparedProcess prepare(NativeCommandRegistry.Entry entry, CommandLimits limits, Duration runtime)
            throws IOException
    {
        var state = entry.state();
        Response variables = control(entry, List.of("show-environment"));
        if (variables.code() != 0) {
            throw new IOException("User manager environment could not be inspected");
        }
        List<String> unset = environmentNames(variables.body());
        List<String> argv = new ArrayList<>(List.of("/usr/bin/systemd-run", "--user", "--quiet", "--wait", "--pipe",
                "--expand-environment=no", "--no-ask-password", "--unit=" + state.unit()));
        argv.addAll(properties(state, limits, runtime, timeout, unset, javaHome));
        argv.addAll(List.of("--", "/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "HOME=/tmp", "TMPDIR=/tmp", "LANG=C", "LC_ALL=C",
                javaHome.resolve("bin/java").toString(), "-XX:ActiveProcessorCount=1", "-Xmx32m", "-XX:+UseSerialGC", "-cp", "/run/codej/launcher.jar",
                NativeCommandLauncher.class.getName(), state.id()));
        return supervisor.prepare(new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, state.id()), argv, Path.of(state.directory()), environment,
                OptionalInt.of(limits.maximumOutputBytes() + 256), limits.maximumOutputBytes() + 1, runtime.plus(timeout), Duration.ofMillis(100)));
    }

    static List<String> environmentNames(String body)
            throws IOException
    {
        var names = new java.util.TreeSet<String>(List.of("LD_PRELOAD", "LD_AUDIT", "LD_LIBRARY_PATH", "GLIBC_TUNABLES", "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GCONV_PATH", "LOCPATH", "NLSPATH", "MALLOC_TRACE"));
        for (String line : body.isEmpty() ? new String[0] : body.split("\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0 || !line.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IOException("User manager environment could not be inspected");
            }
            names.add(line.substring(0, equals));
        }
        return List.copyOf(names);
    }

    static List<String> properties(NativeCommandRegistry.State state, CommandLimits limits, Duration runtime, Duration shutdown, List<String> unset, Path javaHome)
            throws IOException
    {
        List<String> properties = new ArrayList<>(List.of("Type=exec", "Description=codej-command-" + state.id(), "Restart=no", "KillMode=control-group",
                "SendSIGKILL=yes", "TimeoutStartSec=5s", "TimeoutStopSec=" + Math.max(1, shutdown.toMillis()) + "ms",
                "RuntimeMaxSec=" + Math.max(1, runtime.toMillis()) + "ms", "CPUQuota=" + limits.maximumCpuPercent() + "%", "CPUQuotaPeriodSec=100ms",
                "MemoryMax=" + limits.maximumMemoryMiB() * 1024L * 1024L, "MemorySwapMax=0", "TasksMax=" + limits.maximumProcesses(),
                "OOMPolicy=kill", "PrivateUsers=yes", "PrivatePIDs=yes", "PrivateNetwork=yes", "PrivateIPC=yes", "PrivateDevices=yes",
                "ReadOnlyPaths=/dev", "NoNewPrivileges=yes", "CapabilityBoundingSet=", "AmbientCapabilities=", "ProtectSystem=strict", "ProtectKernelTunables=yes",
                "ProtectKernelModules=yes", "ProtectKernelLogs=yes", "ProtectClock=yes", "RestrictNamespaces=yes", "RestrictRealtime=yes",
                "RestrictSUIDSGID=yes", "LockPersonality=yes", "SystemCallArchitectures=native", "SystemCallFilter=@system-service",
                "RestrictAddressFamilies=AF_UNIX", "MountAPIVFS=yes", "RootDirectory=" + safe(state.root()), "WorkingDirectory=/",
                "TemporaryFileSystem=/tmp:rw,nosuid,nodev,size=" + limits.maximumScratchMiB() + "M,nr_inodes=100000",
                "UnsetEnvironment=" + String.join(" ", unset), "ExecStartPre=", "ExecStartPost=", "ExecStop=", "ExecStopPost=", "OnFailure=", "OnSuccess="));
        var bindings = new ArrayList<String>();
        for (Path path : List.of(Path.of("/usr/bin"), Path.of("/bin"), Path.of("/usr/lib"), Path.of("/lib"), Path.of("/lib64"),
                Path.of("/usr/share"), Path.of("/etc/ld.so.cache"), Path.of("/etc/localtime"), javaHome)) {
            if (Files.exists(path)) {
                bindings.add(safe(path) + ":" + safe(path));
            }
        }
        bindings.add(safe(state.root().resolve("workspace")) + ":/workspace");
        bindings.add(safe(Path.of(state.directory()).resolve("launcher.jar")) + ":/run/codej/launcher.jar");
        properties.add("BindReadOnlyPaths=" + String.join(" ", bindings));
        return properties.stream().map(value -> "--property=" + value).toList();
    }

    private static String safe(Path path)
            throws IOException
    {
        if (!path.isAbsolute() || !path.toString().matches("[A-Za-z0-9_./+@-]+") || path.normalize().getParent() == null) {
            throw new IOException("Unsupported native runtime path");
        }
        return path.toString();
    }

    @Override
    public NativeUnit.Status inspect(NativeCommandRegistry.Entry entry)
            throws IOException
    {
        Response result = control(entry, List.of("show", entry.state().unit(), "--property=" + PROPERTIES));
        return NativeUnit.Status.parse(result.body());
    }

    @Override
    public void validate(NativeCommandRegistry.Entry entry, NativeUnit.Status status, CommandLimits limits)
            throws IOException
    {
        NativeUnit.validate(status, entry.state().id(), entry.state().root(), limits);
    }

    @Override
    public boolean stop(NativeCommandRegistry.Entry entry, boolean creationSettled)
            throws IOException
    {
        var before = inspect(entry);
        var state = entry.state();
        if (!before.absent()) {
            identity(state, before);
            entry.registered(before.group());
            control(entry, List.of("stop", state.unit()));
        }
        else if (state.attempted() && !state.registered() && !creationSettled) {
            return false;
        }
        var after = inspect(entry);
        if (!after.absent()) {
            identity(state, after);
        }
        return after.inactive() && after.pid() == 0 && NativeUnit.empty(entry.state().group()) && entry.state().controls().isEmpty();
    }

    private static void identity(NativeCommandRegistry.State state, NativeUnit.Status status)
            throws IOException
    {
        if (!status.description().equals("codej-command-" + state.id()) || !status.root().equals(state.root().toString())) {
            throw new IOException("Native unit ownership could not be verified");
        }
        if (!status.group().isEmpty() && !status.group().endsWith("/" + state.unit())) {
            throw new IOException("Native control group ownership could not be verified");
        }
    }

    @Override
    public void acknowledge(String launch)
    {
        supervisor.acknowledge(launch);
        ManagedProcess process = controls.remove(launch);
        if (process != null) {
            process.close();
        }
    }

    @Override
    public boolean recover(String launch)
    {
        ManagedProcess process = controls.get(launch);
        if (process != null) {
            try {
                process.cancel();
                var proof = process.exit().toCompletableFuture().isDone() ? process.stopAfterWorkFinished() : process.stopAndVerify();
                return proof.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS) == ProcessExit.TreeTermination.VERIFIED_STOPPED;
            }
            catch (Exception unavailable) {
                return false;
            }
        }
        return supervisor.recoverOwnedProcess(launch).outcome() != OwnedProcessRecovery.Outcome.UNVERIFIABLE;
    }

    private record Response(int code, String body)
    {
        @Override
        public String toString() { return "NativeControlResponse[private]"; }
    }

    private Response control(NativeCommandRegistry.Entry entry, List<String> arguments)
            throws IOException
    {
        var argv = new ArrayList<>(List.of("/usr/bin/systemctl", "--user", "--no-pager", "--no-ask-password"));
        argv.addAll(arguments);
        var prepared = supervisor.prepare(new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, entry.state().id()), argv,
                Path.of("/"), environment, OptionalInt.of(65537), 1024, timeout.plusSeconds(1), Duration.ofMillis(100)));
        entry.control(prepared.launchId(), true);
        var output = new ByteArrayOutputStream();
        ManagedProcess process = prepared.start((stream, bytes) -> {
            if (stream == ProcessStream.STDOUT) {
                synchronized (output) {
                    byte[] chunk = new byte[Math.min(bytes.remaining(), Math.max(0, 65537 - output.size()))];
                    bytes.get(chunk);
                    output.writeBytes(chunk);
                }
            }
        });
        controls.put(prepared.launchId(), process);
        try {
            process.closeInput();
            var exit = process.exit().toCompletableFuture().get(timeout.plusSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            if (exit.reason() != ProcessExit.Reason.EXITED
                    || process.stopAfterWorkFinished().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS) != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                throw new IOException("Native control operation could not be verified");
            }
            entry.control(prepared.launchId(), false);
            acknowledge(prepared.launchId());
            if (exit.stdoutTruncated() || output.size() > 65536) {
                throw new IOException("Native control observation exceeds its bound");
            }
            return new Response(exit.exitCode(), output.toString(StandardCharsets.UTF_8));
        }
        catch (Exception failure) {
            process.cancel();
            throw new IOException("Native control operation could not be verified");
        }
    }
}
