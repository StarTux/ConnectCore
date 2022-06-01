package com.winthier.connect.payload;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public final class OnlinePlayer {
    private UUID uuid;
    private String name;
    private String server;
}
