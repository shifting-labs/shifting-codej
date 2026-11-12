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
package io.github.shiftinglabs.codej.cli.spi.model;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Normalized adapter failure. Messages must be sanitized; raw SDK failures/credentials must not escape. */
public final class ModelClientException
        extends RuntimeException
{
    private final Kind kind;
    private final boolean deliveryUncertain;

    public ModelClientException(Kind kind, String message, boolean deliveryUncertain)
    {
        super(checkText(message, "message"));
        this.kind = requireNonNull(kind, "kind is null");
        this.deliveryUncertain = deliveryUncertain;
    }

    public Kind kind() { return kind; }

    /** Whether the provider may have accepted the request; a request ID alone does not make replay safe. */
    public boolean deliveryUncertain() { return deliveryUncertain; }

    public enum Kind
    {
        AUTHENTICATION_REQUIRED,
        RATE_LIMITED,
        TEMPORARILY_UNAVAILABLE,
        INVALID_REQUEST,
        PROTOCOL
    }
}
