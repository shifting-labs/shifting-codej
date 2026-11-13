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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

/**
 * Immutable object-parameter JSON schema: type, properties, required, items, string enums, boolean
 * additionalProperties, description and title. Other assertions fail closed. Parameter codecs
 * must enforce this contract; this value validates the schema, not a call's domain arguments.
 */
public final class ToolSchema
{
    private final String json;
    private final String canonical;
    private final String fingerprint;

    private ToolSchema(String json, ToolSchemaFormat format)
    {
        this.json = requireNonNull(json, "json is null");
        canonical = requireNonNull(requireNonNull(format, "format is null").canonicalize(json), "canonical schema is null");
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Validates one complete supported schema object, without executing a parameter binder.
     *
     * @param json at most 64 KiB of UTF-8 schema JSON
     * @return an immutable schema
     * @throws IllegalArgumentException for malformed or unsupported schemas
     */
    public static ToolSchema of(String json) { return new ToolSchema(json, ToolJson::canonicalSchema); }

    /**
     * Validates a caller-selected schema dialect. This permits compatibility adapters to retain
     * existing schema shapes and limits; the format must validate all assertions it advertises.
     *
     * @param json original schema
     * @param format validation and canonicalization strategy
     * @return an immutable schema
     */
    public static ToolSchema of(String json, ToolSchemaFormat format) { return new ToolSchema(json, format); }

    /**
     * Returns the original schema spelling for model adapters.
     *
     * @return schema JSON
     */
    public String json() { return json; }

    /**
     * Returns a canonical SHA-256 fingerprint. The default format normalizes object-key, required-field and enum ordering;
     * a caller-selected format defines its own canonical representation.
     * This identifies a schema, not implementation behavior or execution safety.
     *
     * @return the lowercase digest
     */
    public String fingerprint() { return fingerprint; }

    @Override
    public boolean equals(Object other) { return other instanceof ToolSchema schema && canonical.equals(schema.canonical); }

    @Override
    public int hashCode() { return canonical.hashCode(); }

    @Override
    public String toString() { return "ToolSchema[" + fingerprint + "]"; }
}
