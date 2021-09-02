package com.winthier.connect.payload;

import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public final class PlayerServerPayload {
    private OnlinePlayer player;
    private String server;

    public String serialize() {
        return new Gson().toJson(this);
    }

    public static PlayerServerPayload deserialize(String json) {
        return new Gson().fromJson(json, PlayerServerPayload.class);
    }
}
