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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Typed systemd/kernel observations. No property is treated as proof without checking the gated process. */
final class NativeUnit
{
    private NativeUnit() {}

    record Status(String load, String active, String description, String root, String group, long pid)
    {
        Status
        {
            if (load == null || active == null || description == null || root == null || group == null || pid < 0) {
                throw new IllegalArgumentException("Invalid native unit observation");
            }
        }

        boolean absent() { return load.equals("not-found"); }

        boolean inactive() { return absent() || Set.of("inactive", "failed").contains(active); }

        static Status parse(String body)
                throws IOException
        {
            Map<String, String> values = properties(body);
            try {
                if ("not-found".equals(values.get("LoadState"))) {
                    if (!required(values, "ActiveState").equals("inactive") || !values.getOrDefault("MainPID", "0").equals("0")
                            || !values.getOrDefault("ControlGroup", "").isEmpty()) {
                        throw new IOException("Contradictory native unit observation");
                    }
                    return new Status("not-found", required(values, "ActiveState"), "", "", "", 0);
                }
                return new Status(required(values, "LoadState"), required(values, "ActiveState"), required(values, "Description"),
                        required(values, "RootDirectory"), required(values, "ControlGroup"), Long.parseLong(required(values, "MainPID")));
            }
            catch (IllegalArgumentException failure) {
                throw new IOException("Invalid native unit observation");
            }
        }
    }

    static Map<String, String> properties(String body)
            throws IOException
    {
        if (body.length() > 65536) {
            throw new IOException("Native unit observation is too large");
        }
        Map<String, String> result = new HashMap<>();
        for (String line : body.split("\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0 || result.putIfAbsent(line.substring(0, equals), line.substring(equals + 1)) != null) {
                throw new IOException("Invalid native unit observation");
            }
        }
        return Map.copyOf(result);
    }

    private static String required(Map<String, String> values, String key)
            throws IOException
    {
        String value = values.get(key);
        if (value == null) {
            throw new IOException("Incomplete native unit observation");
        }
        return value;
    }

    static Path groupPath(String group)
            throws IOException
    {
        if (!group.startsWith("/") || group.length() > 4096 || group.equals("/")) {
            throw new IOException("Invalid native control group");
        }
        Path relative = Path.of(group.substring(1));
        for (Path part : relative) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("Invalid native control group");
            }
        }
        Path current = Path.of("/sys/fs/cgroup");
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Invalid native control group");
            }
        }
        return current;
    }

    static boolean empty(String group)
            throws IOException
    {
        if (group.isEmpty()) {
            return true;
        }
        Path path = groupPath(group);
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return true;
        }
        Map<String, String> events = counters(read(path.resolve("cgroup.events"), 4096));
        return "0".equals(events.get("populated"));
    }

    static void validate(Status unit, String id, Path root, CommandLimits limits)
            throws IOException
    {
        if (unit.absent() || unit.pid() <= 0 || !unit.description().equals("codej-command-" + id)
                || !unit.root().equals(root.toString()) || !unit.group().endsWith("/codej-command-" + id + ".service")) {
            throw new IOException("Native unit identity could not be verified");
        }
        Path process = Path.of("/proc", Long.toString(unit.pid()));
        if (!Files.isSameFile(process.resolve("root"), root)) {
            throw new IOException("Native filesystem isolation could not be verified");
        }
        for (String namespace : List.of("pid", "mnt", "net", "ipc", "user")) {
            if (Files.readSymbolicLink(process.resolve("ns").resolve(namespace)).equals(Files.readSymbolicLink(Path.of("/proc/self/ns", namespace)))) {
                throw new IOException("Native namespace isolation could not be verified");
            }
        }
        Map<String, String> processStatus = status(read(process.resolve("status"), 65536));
        if (!"1".equals(processStatus.get("NoNewPrivs")) || !"2".equals(processStatus.get("Seccomp"))
                || !zeroHex(processStatus.get("CapEff")) || !zeroHex(processStatus.get("CapPrm")) || !zeroHex(processStatus.get("CapBnd"))) {
            throw new IOException("Native privilege isolation could not be verified");
        }
        Path group = groupPath(unit.group());
        validateLimits(read(group.resolve("cpu.max"), 128), read(group.resolve("memory.max"), 128),
                read(group.resolve("memory.swap.max"), 128), read(group.resolve("pids.max"), 128), limits);
        List<Mount> mounts = Mount.parse(read(process.resolve("mountinfo"), 1024 * 1024));
        for (String path : List.of("/", "/workspace", "/proc/sys")) {
            if (mounts.stream().noneMatch(mount -> mount.path().equals(path) && mount.options().contains("ro"))) {
                throw new IOException("Native read-only filesystem could not be verified");
            }
        }
        if (mounts.stream().anyMatch(mount -> (mount.path().equals("/sys/fs/cgroup") || mount.type().equals("tmpfs") && !mount.path().equals("/tmp"))
                && !mount.options().contains("ro"))) {
            throw new IOException("Native control group protection could not be verified");
        }
        Path scratch = process.resolve("root/tmp");
        var store = Files.getFileStore(scratch);
        if (!store.type().equals("tmpfs") || store.getTotalSpace() > limits.maximumScratchMiB() * 1024L * 1024L
                || mounts.stream().noneMatch(mount -> mount.path().equals("/tmp") && mount.type().equals("tmpfs")
                && mount.options().containsAll(Set.of("nosuid", "nodev")))) {
            throw new IOException("Native scratch quota could not be verified");
        }
    }

    static void validateLimits(String cpu, String memory, String swap, String pids, CommandLimits limits)
            throws IOException
    {
        try {
            String[] parts = cpu.strip().split(" +");
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            long quota = Long.parseLong(parts[0]);
            long period = Long.parseLong(parts[1]);
            long memoryBytes = Long.parseLong(memory.strip());
            long processes = Long.parseLong(pids.strip());
            if (quota <= 0 || quota > 1000000000L || period <= 0 || period > 1000000L || quota * 100 > period * limits.maximumCpuPercent()
                    || memoryBytes <= 0 || memoryBytes > limits.maximumMemoryMiB() * 1024L * 1024L || !swap.strip().equals("0")
                    || processes <= 0 || processes > limits.maximumProcesses()) {
                throw new IllegalArgumentException();
            }
        }
        catch (IllegalArgumentException failure) {
            throw new IOException("Native resource quotas could not be verified");
        }
    }

    private static boolean zeroHex(String value) { return value != null && value.matches("0+"); }

    private static Map<String, String> status(String input)
            throws IOException
    {
        Map<String, String> result = new HashMap<>();
        for (String line : input.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                result.put(line.substring(0, colon), line.substring(colon + 1).strip());
            }
        }
        return result;
    }

    private static Map<String, String> counters(String input)
            throws IOException
    {
        Map<String, String> values = new HashMap<>();
        for (String line : input.split("\n")) {
            String[] pair = line.split(" +");
            if (pair.length != 2 || values.putIfAbsent(pair[0], pair[1]) != null) {
                throw new IOException("Invalid native counter observation");
            }
        }
        return values;
    }

    static String read(Path path, int maximum)
            throws IOException
    {
        try (var input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IOException("Native observation exceeded its byte limit");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    record Mount(String path, Set<String> options, String type)
    {
        Mount { options = Set.copyOf(options); }

        static List<Mount> parse(String body)
                throws IOException
        {
            var mounts = new java.util.ArrayList<Mount>();
            for (String line : body.split("\n")) {
                String[] sides = line.split(" - ", -1);
                if (sides.length != 2) {
                    throw new IOException("Invalid native mount observation");
                }
                String[] left = sides[0].split(" ");
                String[] right = sides[1].split(" ");
                if (left.length < 6 || right.length < 3 || mounts.size() >= 8192) {
                    throw new IOException("Invalid native mount observation");
                }
                Set<String> options = new HashSet<>(List.of(left[5].split(",")));
                options.addAll(List.of(right[2].split(",")));
                mounts.add(new Mount(decode(left[4]), options, right[0]));
            }
            return List.copyOf(mounts);
        }

        private static String decode(String value)
                throws IOException
        {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) != '\\') {
                    result.append(value.charAt(i));
                }
                else {
                    if (i + 3 >= value.length() || !value.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                        throw new IOException("Invalid native mount path");
                    }
                    result.append((char) Integer.parseInt(value.substring(i + 1, i + 4), 8));
                    i += 3;
                }
            }
            return result.toString();
        }
    }
}
