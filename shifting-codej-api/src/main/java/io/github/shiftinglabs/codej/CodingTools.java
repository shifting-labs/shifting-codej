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
package io.github.shiftinglabs.codej;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Opt-in limits for the shared API engine's contained coding tools. All calls require approval.
 * Commands are single-process, network-disabled, with a read-only workspace and private scratch.
 * File changes use the patch tool. Currently requires macOS and a working Seatbelt sandbox.
 * Trusted host-supplied {@link Tool} callbacks remain outside this containment boundary.
 *
 * @param maximumFileBytes maximum UTF-8 file size read or produced by a patch
 * @param maximumSearchBytes maximum bytes examined by one literal search
 * @param maximumEntries maximum filesystem entries visited by one scan
 * @param maximumCommandSeconds maximum lifetime of one command
 * @param maximumOutputBytes maximum retained bytes per command output stream
 */
public record CodingTools(int maximumFileBytes, int maximumSearchBytes, int maximumEntries, int maximumCommandSeconds, int maximumOutputBytes)
{
    /**
     * Creates coding tool limits, rejecting zero, inconsistent or excessive bounds.
     *
     * @param maximumFileBytes maximum file size in bytes
     * @param maximumSearchBytes maximum bytes examined per search, at least the file limit
     * @param maximumEntries maximum entries visited per scan
     * @param maximumCommandSeconds command timeout, at most 300 seconds
     * @param maximumOutputBytes maximum retained bytes per command stream
     * @throws IllegalArgumentException when a bound is outside its supported range
     */
    public CodingTools
    {
        checkArgument(maximumFileBytes > 0 && maximumFileBytes <= 16 * 1024 * 1024, "invalid file limit");
        checkArgument(maximumSearchBytes >= maximumFileBytes && maximumSearchBytes <= 1024 * 1024 * 1024, "invalid search limit");
        checkArgument(maximumEntries > 0 && maximumEntries <= 1000000, "invalid entry limit");
        checkArgument(maximumCommandSeconds > 0 && maximumCommandSeconds <= 300, "invalid command timeout");
        checkArgument(maximumOutputBytes > 0 && maximumOutputBytes <= 65536, "invalid output limit");
    }

    /**
     * Conservative defaults: 1 MiB files, 64 MiB searches, 100,000 entries, 30 seconds, 8 KiB per stream.
     *
     * @return default coding tool limits
     */
    public static CodingTools defaults() { return new CodingTools(1024 * 1024, 64 * 1024 * 1024, 100000, 30, 8192); }
}
