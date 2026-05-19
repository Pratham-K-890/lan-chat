package com.lanchat.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;

import java.util.List;

/**
 * Central Jackson ObjectMapper singleton.
 *
 * Rules:
 *  - Never create ObjectMapper anywhere else — it is expensive and thread-safe only as singleton.
 *  - Use toJson() / fromJson() for Envelope (de)serialization.
 *  - Use buildPayload() / envelope() for concise packet construction.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    // ── Serialization ────────────────────────────────────────────────

    /** Serialize an Envelope to a single-line JSON string (no trailing newline). */
    public static String toJson(Envelope env) {
        try {
            return MAPPER.writeValueAsString(env);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed: " + env, e);
        }
    }

    /** Deserialize a line from the socket into an Envelope. */
    public static Envelope fromJson(String line) {
        try {
            return MAPPER.readValue(line, Envelope.class);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed for: " + line, e);
        }
    }

    // ── Payload builders ─────────────────────────────────────────────

    /**
     * Build a payload ObjectNode from alternating key-value pairs.
     * Supported value types: String, Long, Integer, Boolean.
     *
     * Example:
     *   buildPayload("username", "alice", "token", tokenStr)
     */
    public static ObjectNode buildPayload(Object... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object val = keyValues[i + 1];
            if      (val instanceof String  s) node.put(key, s);
            else if (val instanceof Long    l) node.put(key, l);
            else if (val instanceof Integer n) node.put(key, n);
            else if (val instanceof Boolean b) node.put(key, b);
            else if (val instanceof Double  d) node.put(key, d);
            // null values are silently skipped
        }
        return node;
    }

    /**
     * Convenience: create a typed Envelope in one line.
     * Example:
     *   JsonUtil.envelope(MessageType.PING)
     *   JsonUtil.envelope(MessageType.AUTH_SUCCESS, "username", "alice", "token", tok)
     */
    public static Envelope envelope(MessageType type, Object... kvPairs) {
        return new Envelope(type, buildPayload(kvPairs));
    }

    /** Build a payload that contains a JSON array of strings under a key. */
    public static ObjectNode buildPayloadWithList(String key, List<String> items) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode arr   = MAPPER.createArrayNode();
        items.forEach(arr::add);
        node.set(key, arr);
        return node;
    }

    /** Empty payload node (for PING, PONG, etc.) */
    public static ObjectNode emptyPayload() {
        return MAPPER.createObjectNode();
    }

    /** Expose the raw mapper for complex cases (e.g., building ArrayNode). */
    public static ObjectMapper mapper() { return MAPPER; }
}
