package com.winthier.connect.payload;

import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public final class RemoteCommand {
    private OnlinePlayer sender;
    private String[] args;

    public String serialize() {
        return new Gson().toJson(this);
    }

    public static RemoteCommand deserialize(String json) {
        return new Gson().fromJson(json, RemoteCommand.class);
    }
}
