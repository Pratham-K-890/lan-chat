package com.lanchat.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Every packet on the wire is a JSON-serialized Envelope followed by '\n'.
 *
 * Wire format:
 *   {"type":"BROADCAST","payload":{"from":"alice","text":"hello","timestamp":...}}
 *
 * The server reads type to route; payload shape depends on type.
 * This class is shared between the Java server and Android client via common.jar.
 */
public class Envelope {

    public MessageType type;
    public JsonNode payload;   // raw JSON node — cast/read on the receiver side

    /** Required by Jackson for deserialization */
    public Envelope() {}

    public Envelope(MessageType type, JsonNode payload) {
        this.type    = type;
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "Envelope{type=" + type + ", payload=" + payload + "}";
    }
}
