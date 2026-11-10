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

// Builds a Codex agent with only the published jar on the classpath, so the SDK downloads OpenAI's Codex as a user's first
// build would, and checks that the Codex it runs is the pinned release. A fresh home is not signed in, which is fine here.
//
//   java -cp shifting-codej/target/shifting-codej-<version>.jar scripts/CodexDownloadCheck.java <empty state folder>

import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Codex;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public final class CodexDownloadCheck
{
    private CodexDownloadCheck() {}

    public static void main(String[] args)
    {
        try (Agent codex = Codex.builder().stateDirectory(Path.of(args[0])).build()) {
            AgentStatus status = codex.status();
            System.out.println(status.readiness() + " " + status.version().orElse("no version") + ": " + status.detail());
            if (!status.version().equals(Optional.of(Codex.VERSION))
                    || !Set.of(AgentStatus.Readiness.READY, AgentStatus.Readiness.SIGN_IN_REQUIRED).contains(status.readiness())) {
                System.exit(1);
            }
        }
    }
}
