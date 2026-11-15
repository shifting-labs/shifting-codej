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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestNativeCommandProtocol
{
    private static final String NONCE = UUID.randomUUID().toString();
    private static final NativeCommandLauncher.Request REQUEST = new NativeCommandLauncher.Request(List.of("/bin/echo", "$literal %literal \"雪\""), "src");

    private static NativeCommandLauncher.Request decode(byte[] bytes, String nonce)
            throws IOException
    {
        return NativeCommandLauncher.read(new DataInputStream(new ByteArrayInputStream(bytes)), nonce);
    }

    private static byte[] committed(byte[] frame)
    {
        byte[] result = Arrays.copyOf(frame, frame.length + 1);
        result[frame.length] = (byte) NativeCommandLauncher.COMMIT;
        return result;
    }

    @Test
    void completeFrameRequiresCommitAndEndOfInput()
            throws Exception
    {
        byte[] frame = NativeCommandLauncher.encode(NONCE, REQUEST);
        assertThat(decode(committed(frame), NONCE)).isEqualTo(REQUEST);
        assertThat(REQUEST.toString()).doesNotContain("literal", "雪");
        assertThatThrownBy(() -> decode(frame, NONCE)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(Arrays.copyOf(committed(frame), frame.length + 2), NONCE)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(committed(frame), UUID.randomUUID().toString())).isInstanceOf(IOException.class);
        for (int length : List.of(0, 3, 12, frame.length - 1)) {
            assertThatThrownBy(() -> decode(Arrays.copyOf(frame, length), NONCE)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void malformedFieldsAndRequestsAreRejectedBeforeExecution()
            throws Exception
    {
        byte[] oversized = NativeCommandLauncher.encode(NONCE, REQUEST);
        ByteBuffer.wrap(oversized).putInt(4, Integer.MAX_VALUE);
        assertThatThrownBy(() -> decode(committed(oversized), NONCE)).isInstanceOf(IOException.class);
        byte[] frame = NativeCommandLauncher.encode(NONCE, REQUEST);
        frame[8] = (byte) 0xFF;
        byte[] malformed = committed(frame);
        assertThatThrownBy(() -> decode(malformed, NONCE)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new NativeCommandLauncher.Request(List.of("relative"), ".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativeCommandLauncher.Request(List.of("/bin/true"), "../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativeCommandLauncher.Request(List.of("/bin/echo", "x".repeat(32768)), ".")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitPropertiesRejectAmbiguousOrIncompleteObservations()
            throws Exception
    {
        var absent = NativeUnit.Status.parse("LoadState=not-found\nActiveState=inactive\n");
        assertThat(absent.absent()).isTrue();
        assertThat(absent.inactive()).isTrue();
        assertThatThrownBy(() -> NativeUnit.Status.parse("LoadState=not-found\nActiveState=active\nMainPID=1")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.Status.parse("LoadState=loaded\nActiveState=active\n")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.properties("MainPID=1\nMainPID=2")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.properties("MainPID")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.properties("a=" + "x".repeat(65536))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.groupPath("/../outside")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.groupPath("/")).isInstanceOf(IOException.class);
    }

    @Test
    void kernelQuotasMustAllBeFiniteAndNoHigherThanRequested()
            throws Exception
    {
        var limits = CommandLimits.defaults();
        String memory = Long.toString(limits.maximumMemoryMiB() * 1024L * 1024L);
        NativeUnit.validateLimits("100000 100000\n", memory, "0\n", "64\n", limits);
        for (String cpu : List.of("max 100000", "100001 100000", "0 100000", "9223372036854775807 100000", "1")) {
            assertThatThrownBy(() -> NativeUnit.validateLimits(cpu, memory, "0", "64", limits)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> NativeUnit.validateLimits("100000 100000", "max", "0", "64", limits)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.validateLimits("100000 100000", memory, "1", "64", limits)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.validateLimits("100000 100000", memory, "0", "65", limits)).isInstanceOf(IOException.class);
    }

    @Test
    void mountObservationsDecodeEscapesAndKeepEnforcementFlags()
            throws Exception
    {
        var mount = NativeUnit.Mount.parse("11 10 0:1 / /path\\040name ro,nosuid - tmpfs tmpfs rw,nodev\n").getFirst();
        assertThat(mount.path()).isEqualTo("/path name");
        assertThat(mount.options()).contains("ro", "nosuid", "nodev");
        assertThat(mount.type()).isEqualTo("tmpfs");
        assertThatThrownBy(() -> NativeUnit.Mount.parse("invalid")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativeUnit.Mount.parse("11 10 0:1 / /bad\\xyz ro - tmpfs tmpfs ro")).isInstanceOf(IOException.class);
    }

    @Test
    void unitArgumentsContainOnlyHostConfigurationAndSanitizedVariableNames()
            throws Exception
    {
        var state = new NativeCommandRegistry.State(1, NONCE, NONCE, "/tmp/codej-native-test", "key", Optional.empty(), List.of(), false, false, "", false, false);
        var names = SystemdCommandService.environmentNames("TOKEN='secret value'\nPATH=/usr/bin\n");
        assertThat(names).contains("TOKEN", "LD_PRELOAD", "JAVA_TOOL_OPTIONS").doesNotContain("secret value");
        assertThat(SystemdCommandService.environmentNames("")).contains("LD_AUDIT");
        assertThatThrownBy(() -> SystemdCommandService.environmentNames("invalid value")).isInstanceOf(IOException.class);
        List<String> args = SystemdCommandService.properties(state, CommandLimits.defaults(), Duration.ofSeconds(3), Duration.ofSeconds(1), names, Path.of("/usr/lib/jvm/java"));
        assertThat(args).contains("--property=PrivatePIDs=yes", "--property=PrivateNetwork=yes", "--property=MemoryMax=536870912", "--property=TasksMax=64",
                "--property=KillMode=control-group", "--property=ReadOnlyPaths=/dev");
        assertThat(String.join(" ", args)).doesNotContain("secret value");
    }

    @Test
    void outputRemovesReadinessFrameAndRetainsBoundedIndependentStreams()
            throws Exception
    {
        var capture = new NativeCommandBackend.Capture(4);
        capture.nonce = NONCE;
        byte[] ready = ("codej-native-ready:" + NONCE + "\n").getBytes(StandardCharsets.US_ASCII);
        capture.accept(io.github.shiftinglabs.codej.cli.process.ProcessStream.STDOUT, ByteBuffer.wrap(Arrays.copyOf(ready, 7)));
        assertThat(capture.ready).isNotDone();
        var tail = new ByteArrayOutputStream();
        tail.writeBytes(Arrays.copyOfRange(ready, 7, ready.length));
        tail.writeBytes("123456".getBytes(StandardCharsets.UTF_8));
        capture.accept(io.github.shiftinglabs.codej.cli.process.ProcessStream.STDOUT, ByteBuffer.wrap(tail.toByteArray()));
        capture.accept(io.github.shiftinglabs.codej.cli.process.ProcessStream.STDERR, ByteBuffer.wrap("error".getBytes(StandardCharsets.UTF_8)));
        assertThat(capture.ready).isCompleted();
        assertThat(capture.output()).isEqualTo(new CommandOutput("1234", "erro", true, true));
        var invalid = new NativeCommandBackend.Capture(4);
        invalid.nonce = NONCE;
        invalid.accept(io.github.shiftinglabs.codej.cli.process.ProcessStream.STDOUT, ByteBuffer.wrap("wrong\n".getBytes(StandardCharsets.US_ASCII)));
        assertThat(invalid.ready).isCompletedExceptionally();
    }
}
