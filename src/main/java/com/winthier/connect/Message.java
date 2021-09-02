package com.winthier.connect;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@Getter @RequiredArgsConstructor @ToString
public final class Message {
    protected final String channel;
    protected final String from;
    protected final String to;
    protected final String payload;
    protected final long created = System.currentTimeMillis();

    String serialize() {
        return new Gson().toJson(this);
    }

    static Message deserialize(String serial) {
        return new Gson().fromJson(serial, Message.class);
    }
}
