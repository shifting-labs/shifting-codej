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
package io.github.shiftinglabs.codej.cli.spi.retention;

import java.util.Optional;

/** Borrowed thread-safe policy; callbacks are bounded, cooperative and perform no inference, I/O or tool effects. */
@FunctionalInterface
public interface AuditRetentionPolicy
{
    Optional<AuditRetention> retain(AuditRetentionContext context);

    /** Keep at least this many complete exchanges; discard only prefixes covered by committed summary memory. */
    static AuditRetentionPolicy keepLatest(int retainExchanges, int maximumAttachmentDeletions)
    {
        var plan = new AuditRetention(retainExchanges, maximumAttachmentDeletions);
        return context -> Optional.of(plan);
    }
}
