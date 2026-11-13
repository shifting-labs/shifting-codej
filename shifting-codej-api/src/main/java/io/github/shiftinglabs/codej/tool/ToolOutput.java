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
package io.github.shiftinglabs.codej.tool;

/**
 * Typed model-facing output. Encoders can use different implementations while the dispatcher
 * enforces its effective output budget and redaction. Default factories produce bounded text.
 */
public interface ToolOutput
{
    /**
     * Returns model-facing text. Callers redact before recording or displaying it.
     *
     * @return the text
     */
    String text();

    /**
     * Returns its media type. The initial engine projection supports text/plain and application/json;
     * other forms require explicit capability negotiation and must not silently become plain text.
     *
     * @return the media type
     */
    String mediaType();

    /**
     * Creates plain text output. The engine can apply a smaller per-call budget.
     *
     * @param text at most 65,536 characters
     * @return immutable output whose string representation hides its data
     */
    static ToolOutput text(String text) { return new BoundedToolOutput(text, "text/plain"); }

    /**
     * Creates structured JSON object output, validating its envelope.
     *
     * @param json a single JSON object of at most 64 KiB in UTF-8
     * @return immutable output
     */
    static ToolOutput json(String json)
    {
        return new BoundedToolOutput(json, "application/json");
    }
}
