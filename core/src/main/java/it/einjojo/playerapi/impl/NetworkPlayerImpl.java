package it.einjojo.playerapi.impl;

import it.einjojo.playerapi.AfkServiceApi;
import it.einjojo.playerapi.NetworkPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NetworkPlayerImpl extends OfflineNetworkPlayerImpl implements NetworkPlayer {

    private final String serverName;
    private final String proxyName;
    private final long sessionId;
    private final AfkServiceApi afkServiceApi;

    public NetworkPlayerImpl(UUID uniqueId, String name, long firstPlayed, long lastPlayed,
                             long playtime, boolean online, String serverName, String proxyName,
                             long sessionId, @NotNull AfkServiceApi afkServiceApi) {
        super(uniqueId, name, firstPlayed, lastPlayed, playtime, online);
        this.serverName = serverName;
        this.proxyName = proxyName;
        this.sessionId = sessionId;
        this.afkServiceApi = afkServiceApi;
    }

    @Override
    public Optional<String> getConnectedServerName() {
        return Optional.ofNullable(serverName);
    }

    @Override
    public String getConnectedProxyName() {
        return proxyName;
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public CompletableFuture<Boolean> isAfk() {
        return afkServiceApi.isAfk(getUniqueId());
    }

    @Override
    public CompletableFuture<Void> setAfk(boolean afk) {
        return afkServiceApi.setAfk(getUniqueId(), afk);
    }
}
