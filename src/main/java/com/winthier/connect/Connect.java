package com.winthier.connect;

import com.winthier.connect.payload.OnlinePlayer;
import com.winthier.connect.payload.RemoteCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

@Getter
public final class Connect implements Runnable {
    protected static final String KEY_SERVER_MAP = "cavetale.connect.server_map";
    protected static final String KEY_SERVER_QUEUE = "cavetale.connect.server_queue";
    protected static final String KEY_PLAYER_LIST = "cavetale.connect.player_list";
    protected final String serverName;
    protected final ConnectHandler handler;
    protected final String messageQueue;
    protected final JedisPool jedisPool;
    protected volatile boolean shouldStop = false;
    protected volatile boolean hasPlayerList = false;
    @Getter protected static Connect instance = null;
    protected Map<String, List<OnlinePlayer>> cachedPlayerMap = Map.of();
    protected List<OnlinePlayer> cachedPlayerList = List.of();
    protected List<String> cachedServerList = List.of();
    protected int cachedPlayerCount;
    private long lastCachedPlayersUpdate;

    // --- Client

    public Connect(final String serverName, final ConnectHandler handler) {
        if (serverName == null) throw new NullPointerException("serverName cannot be null");
        if (handler == null) throw new NullPointerException("handler cannot be null");
        this.serverName = serverName;
        this.handler = handler;
        messageQueue = KEY_SERVER_QUEUE + "." + serverName;
        jedisPool = new JedisPool();
        jedisPool.setMaxWait(Duration.ofSeconds(1));
        instance = this;
    }

    public Connect(final String serverName, final ConnectHandler handler, final String host, final int port, final String user, final String password) {
        if (serverName == null) throw new NullPointerException("serverName cannot be null");
        if (handler == null) throw new NullPointerException("handler cannot be null");
        this.serverName = serverName;
        this.handler = handler;
        messageQueue = KEY_SERVER_QUEUE + "." + serverName;
        jedisPool = new JedisPool(host, port, user, password);
        jedisPool.setMaxWait(Duration.ofSeconds(1));
        instance = this;
    }

    public void stop() {
        shouldStop = true;
        unregisterServerList();
        broadcast("DISCONNECT", null, false);
    }

    private void updateCachedPlayers(Jedis jedis) {
        int newCount = 0;
        Map<String, List<OnlinePlayer>> newMap = listPlayers(jedis);
        List<OnlinePlayer> newList = new ArrayList<>();
        for (List<OnlinePlayer> list : newMap.values()) {
            newList.addAll(list);
            newCount += list.size();
        }
        this.cachedPlayerMap = newMap;
        this.cachedPlayerList = newList;
        this.cachedPlayerCount = newCount;
        lastCachedPlayersUpdate = System.currentTimeMillis();
        this.cachedServerList = listServers();
    }

    @Override
    public void run() {
        try (Jedis jedis = jedisPool.getResource()) {
            updateCachedPlayers(jedis);
            registerServerList();
            broadcast("CONNECT", null, false);
            long lastRegister = Instant.now().getEpochSecond();
            while (!shouldStop) {
                try {
                    List<String> inp = jedis.brpop(1, messageQueue);
                    if (inp != null && inp.size() == 2) {
                        Message message = Message.deserialize(inp.get(1));
                        handler.handleMessage(message);
                        switch (message.channel) {
                        case "REMOTE":
                            RemoteCommand rcmd = RemoteCommand.deserialize(message.payload);
                            handler.handleRemoteCommand(rcmd.getSender(),
                                                        message.from, rcmd.getArgs());
                            break;
                        case "CONNECT":
                            updateCachedPlayers(jedis);
                            handler.handleRemoteConnect(message.from);
                            break;
                        case "DISCONNECT":
                            updateCachedPlayers(jedis);
                            handler.handleRemoteDisconnect(message.from);
                            break;
                        case "PLAYER_LIST_UPDATE":
                            updateCachedPlayers(jedis);
                            break;
                        default:
                            break;
                        }
                    }
                    long now = Instant.now().getEpochSecond();
                    if (now - lastRegister >= 10) {
                        lastRegister = now;
                        registerServerList();
                        if (hasPlayerList) keepPlayerListAlive();
                    }
                    if (System.currentTimeMillis() - lastCachedPlayersUpdate > 60_000L) {
                        updateCachedPlayers(jedis);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void registerServerList() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(KEY_SERVER_MAP, serverName, "" + Instant.now().getEpochSecond());
        }
    }

    void unregisterServerList() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel(KEY_SERVER_MAP, serverName);
        }
    }

    // --- Sending

    public List<String> listServers() {
        List<String> result = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> allServers = jedis.hgetAll(KEY_SERVER_MAP);
            long now = Instant.now().getEpochSecond();
            for (Map.Entry<String, String> entry: allServers.entrySet()) {
                Long seen = Long.parseLong(entry.getValue());
                String key = entry.getKey();
                if (now - seen > 60) {
                    jedis.hdel(KEY_SERVER_MAP, key);
                } else {
                    result.add(key);
                }
            }
        }
        return result;
    }

    public boolean send(String target, String channel, String payload) {
        final Message message = new Message(channel, serverName, target, payload);
        final String rediskey = KEY_SERVER_QUEUE + "." + target;
        try (Jedis jedis = jedisPool.getResource()) {
            Transaction t = jedis.multi();
            t.lpush(rediskey, message.serialize());
            t.expire(rediskey, 10L);
            t.exec();
        }
        return true;
    }

    // --- Broadcast

    private void broadcast(String channel, String payload, boolean all) {
        for (String clientname: listServers()) {
            if (!all && clientname.equals(serverName)) continue;
            send(clientname, channel, payload);
        }
    }

    public void broadcast(String channel, String payload) {
        broadcast(channel, payload, false);
    }

    public void broadcastAll(String channel, String payload) {
        broadcast(channel, payload, true);
    }

    public void ping() {
        broadcast("PING", null, false);
    }

    public void broadcastRemoteCommand(OnlinePlayer sender, String[] args) {
        broadcast("REMOTE", new RemoteCommand(sender, args).serialize());
    }

    public void sendRemoteCommand(String target, OnlinePlayer sender, String[] args) {
        send(target, "REMOTE", new RemoteCommand(sender, args).serialize());
    }

    // --- Player List

    /**
     * Call whenever a player joins or quits.
     */
    public void updatePlayerList(Collection<OnlinePlayer> players) {
        hasPlayerList = true;
        if (players.isEmpty()) {
            removePlayerList();
            return;
        }
        HashMap<String, String> map = new HashMap<>();
        for (OnlinePlayer player : players) {
            // Use names as values as they may theoretically not be unique.
            map.put(player.getUuid().toString(), player.getName());
        }
        final String key = KEY_PLAYER_LIST + "." + serverName;
        try (Jedis jedis = jedisPool.getResource()) {
            Transaction t = jedis.multi();
            t.del(key);
            t.hset(key, map);
            t.expire(key, 60L);
            t.exec();
            updateCachedPlayers(jedis);
        }
        broadcast("PLAYER_LIST_UPDATE", "");
    }

    private void keepPlayerListAlive() {
        final String key = KEY_PLAYER_LIST + "." + serverName;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(key, 60L);
        }
    }

    private void removePlayerList() {
        final String key = KEY_PLAYER_LIST + "." + serverName;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        }
    }

    public Map<String, List<OnlinePlayer>> listPlayers(Jedis jedis) {
        HashMap<String, List<OnlinePlayer>> result = new HashMap<>();
        for (String other : listServers()) {
            List<OnlinePlayer> playerList = new ArrayList<>();
            result.put(other, playerList);
            for (Map.Entry<String, String> playerEntry : jedis.hgetAll(KEY_PLAYER_LIST + "." + other).entrySet()) {
                UUID uuid = UUID.fromString(playerEntry.getKey());
                playerList.add(new OnlinePlayer(uuid, playerEntry.getValue(), other));
            }
        }
        return result;
    }

    public Map<String, List<OnlinePlayer>> listPlayers() {
        try (Jedis jedis = jedisPool.getResource()) {
            return listPlayers(jedis);
        }
    }

    public List<OnlinePlayer> getOnlinePlayers() {
        List<OnlinePlayer> result = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            for (String other: listServers()) {
                for (Map.Entry<String, String> playerEntry : jedis.hgetAll(KEY_PLAYER_LIST + "." + other).entrySet()) {
                    UUID uuid = UUID.fromString(playerEntry.getKey());
                    result.add(new OnlinePlayer(uuid, playerEntry.getValue(), other));
                }
            }
        }
        return result;
    }

    public OnlinePlayer findOnlinePlayer(String name) {
        try (Jedis jedis = jedisPool.getResource()) {
            for (String other : listServers()) {
                for (Map.Entry<String, String> playerEntry : jedis.hgetAll(KEY_PLAYER_LIST + "." + other).entrySet()) {
                    if (playerEntry.getValue().equals(name)) {
                        UUID uuid = UUID.fromString(playerEntry.getKey());
                        return new OnlinePlayer(uuid, playerEntry.getValue(), other);
                    }
                }
            }
        }
        return null;
    }

    public OnlinePlayer findOnlinePlayer(UUID uuid) {
        String uuidString = uuid.toString();
        try (Jedis jedis = jedisPool.getResource()) {
            for (String other : listServers()) {
                for (Map.Entry<String, String> playerEntry : jedis.hgetAll(KEY_PLAYER_LIST + "." + other).entrySet()) {
                    if (playerEntry.getKey().equals(uuidString)) {
                        return new OnlinePlayer(uuid, playerEntry.getValue(), other);
                    }
                }
            }
        }
        return null;
    }

    public String findServerOfPlayer(UUID uuid) {
        String uuidString = uuid.toString();
        try (Jedis jedis = jedisPool.getResource()) {
            for (String other : listServers()) {
                for (Map.Entry<String, String> playerEntry : jedis.hgetAll(KEY_PLAYER_LIST + "." + other).entrySet()) {
                    if (playerEntry.getKey().equals(uuidString)) {
                        return other;
                    }
                }
            }
        }
        return null;
    }
}
