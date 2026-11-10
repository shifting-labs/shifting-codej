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
package io.github.shiftinglabs.codej.cli;

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * The instructions a provider session starts with, in one order on both lanes: the command scope, plan guidance unless the
 * session is read-only, the host application's managed instructions, then guidance for the tools this session carries. Only the plan
 * tool and the extra tools differ between lanes.
 */
public final class ProviderInstructions
{
    private ProviderInstructions() {}

    public static String compose(AgentPermissionScope scope, String planInstruction, String managedInstructions, String... toolInstructions)
    {
        return Stream.concat(
                        Stream.of(scope.standingInstructions(), scope.access().restricted() ? "" : planInstruction, managedInstructions),
                        Stream.of(toolInstructions))
                .filter(part -> !part.isBlank())
                .collect(joining("\n\n"));
    }

    /**
     * A short name for what a session was set up with: the first half follows the host application's own instructions for it, composed as
     * above without the managed instructions, and the second the digest of its tools. Managed instructions carry each task's
     * own context, such as its pull request, so with them every task would have a name of its own. Turns with one name ran
     * with the same setup, so their results can be compared before and after a change to either.
     */
    public static String setup(AgentPermissionScope scope, String planInstruction, Optional<String> toolsDigest, String... toolInstructions)
    {
        return digest(compose(scope, planInstruction, "", toolInstructions)).substring(0, 8) + "-" + toolsDigest.orElseGet(() -> digest("")).substring(0, 8);
    }

    private static String digest(String text)
    {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
    }
}
