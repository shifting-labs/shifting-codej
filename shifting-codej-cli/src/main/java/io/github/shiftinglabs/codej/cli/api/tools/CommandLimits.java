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

import static com.google.common.base.Preconditions.checkArgument;

/** Host-selected hard bounds for one command tree, never model-selected authority. PID limits include threads. */
public record CommandLimits(int maximumProcesses, int maximumCpuPercent, int maximumMemoryMiB,
        int maximumScratchMiB, int maximumSeconds, int maximumOutputBytes)
{
    public CommandLimits
    {
        checkArgument(maximumProcesses >= 2 && maximumProcesses <= 512, "invalid process limit");
        checkArgument(maximumCpuPercent > 0 && maximumCpuPercent <= 800, "invalid CPU limit");
        checkArgument(maximumMemoryMiB >= 64 && maximumMemoryMiB <= 16384, "invalid memory limit");
        checkArgument(maximumScratchMiB >= 1 && maximumScratchMiB <= 1024, "invalid scratch limit");
        checkArgument(maximumSeconds > 0 && maximumSeconds <= 300, "invalid command timeout");
        checkArgument(maximumOutputBytes > 0 && maximumOutputBytes <= 65536, "invalid output limit");
    }

    /** One CPU, 512 MiB memory, 64 MiB scratch, 64 PIDs/threads, 30 seconds and 8 KiB per output stream. */
    public static CommandLimits defaults() { return new CommandLimits(64, 100, 512, 64, 30, 8192); }
}
