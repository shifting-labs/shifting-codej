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

import static com.google.common.base.Preconditions.checkArgument;

/** Explicit loss of covered audit data; attachment deletion is separately bounded and disabled at zero. */
public record AuditRetention(int retainExchanges, int maximumAttachmentDeletions)
{
    public AuditRetention
    {
        checkArgument(retainExchanges >= 0, "retained exchanges must not be negative");
        checkArgument(maximumAttachmentDeletions >= 0 && maximumAttachmentDeletions <= 1024, "invalid attachment deletion limit");
    }
}
