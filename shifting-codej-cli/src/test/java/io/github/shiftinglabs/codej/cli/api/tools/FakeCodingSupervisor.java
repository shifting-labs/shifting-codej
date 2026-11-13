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
import io.github.shiftinglabs.codej.cli.process.ProcessIdentity;
import io.github.shiftinglabs.codej.cli.process.ProcessOutputSink;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public final class FakeCodingSupervisor
        implements ProcessSupervisor
{
    public final List<ProcessSpec> specs = new ArrayList<>();
    public final List<String> acknowledged = new ArrayList<>();
    public boolean verified = true;
    public volatile CompletableFuture<ProcessExit> pendingExit;
    public final CountDownLatch waiting = new CountDownLatch(1);
    public Runnable beforeStart = () -> {};

    @Override
    public PreparedProcess prepare(ProcessSpec spec)
    {
        specs.add(spec);
        String id = "launch-" + specs.size();
        return new PreparedProcess()
        {
            @Override
            public String launchId() { return id; }

            @Override
            public ManagedProcess start(ProcessOutputSink sink)
            {
                beforeStart.run();
                return new ManagedProcess()
                {
                    @Override
                    public String launchId() { return id; }

                    @Override
                    public long pid() { return 1; }

                    @Override
                    public Optional<List<ProcessIdentity>> observedDescendants() { return Optional.of(List.of()); }

                    @Override
                    public CompletionStage<Void> write(ByteBuffer bytes) { return CompletableFuture.completedFuture(null); }

                    @Override
                    public void closeInput() {}

                    @Override
                    public CompletionStage<ProcessExit> exit()
                    {
                        if (pendingExit != null) {
                            waiting.countDown();
                            return pendingExit;
                        }
                        return CompletableFuture.completedFuture(new ProcessExit(0, ProcessExit.Reason.EXITED,
                                verified ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE,
                                Duration.ZERO, false, false));
                    }

                    @Override
                    public void cancel() {}

                    @Override
                    public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
                    {
                        return CompletableFuture.completedFuture(verified ? ProcessExit.TreeTermination.VERIFIED_STOPPED : ProcessExit.TreeTermination.UNVERIFIABLE);
                    }

                    @Override
                    public CompletionStage<ProcessExit.TreeTermination> stopAfterWorkFinished() { return stopAndVerify(); }

                    @Override
                    public void close() {}
                };
            }
        };
    }

    @Override
    public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("borrowed supervisor recovery"); }

    @Override
    public OwnedProcessRecovery recoverOwnedProcess(String id) { throw new AssertionError("borrowed supervisor recovery"); }

    @Override
    public boolean launchedHere(String id) { return true; }

    @Override
    public void acknowledge(String id) { acknowledged.add(id); }
}
