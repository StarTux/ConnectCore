package com.winthier.connect;

import com.winthier.connect.payload.OnlinePlayer;

public abstract class AbstractConnectHandler implements ConnectHandler {
    @Override public void handleRemoteConnect(String name) { }
    @Override public void handleRemoteDisconnect(String name) { }
    @Override public void handleMessage(Message message) { }
    @Override public void handleRemoteCommand(OnlinePlayer sender, String server, String[] args) { }
}
