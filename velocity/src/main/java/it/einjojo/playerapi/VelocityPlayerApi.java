package it.einjojo.playerapi;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.grpc.ManagedChannel;
import it.einjojo.playerapi.config.RedisConnectionConfiguration;
import it.einjojo.playerapi.impl.AbstractPlayerApi;
import it.einjojo.playerapi.impl.PlayerMapper;
import it.einjojo.protocol.player.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class VelocityPlayerApi extends AbstractPlayerApi implements Consumer<ConnectRequest> {
    private static final String PROXY_NAME = Optional.ofNullable(System.getenv("PLAYER_API_PROXY_NAME")).orElse("NO_ENV.PLAYER_API_PROXY_NAME");
    private static final Logger log = LoggerFactory.getLogger(VelocityPlayerApi.class);
    private final LocalOnlinePlayerAccessor localOnlinePlayerAccessor;
    private final RedisPubSubHandler redisPubSubHandler;
    private final ProxyServer proxyServer;

    /**
     * Constructor for AbstractPlayerApi.
     *
     * @param channel  the gRPC channel to communicate with the player service
     * @param executor the executor to run the callbacks on
     */
    public VelocityPlayerApi(ManagedChannel channel, Executor executor, ProxyServer proxyServer, RedisConnectionConfiguration redisConnectionConfiguration) {
        super(channel, executor);
        this.proxyServer = proxyServer;
        this.localOnlinePlayerAccessor = new VelocityLocalPlayerAccessor(proxyServer);
        this.redisPubSubHandler = new RedisPubSubHandler(redisConnectionConfiguration, executor);
        this.redisPubSubHandler.setConnectRequestConsumer(this);
    }

    @Override
    public LocalOnlinePlayerAccessor getLocalOnlinePlayerAccessor() {
        return localOnlinePlayerAccessor;
    }

    @Override
    protected RedisPubSubHandler getRedisPubSubHandler() {
        return redisPubSubHandler;
    }


    public CompletableFuture<NetworkPlayer> handleLogin(Player player) {
        // Extract skin properties
        String skinTexture = null;
        String skinSignature = null;
        for (var prop : player.getGameProfile().getProperties()) {
            if (prop.getName().equals("textures")) {
                skinTexture = prop.getValue();
                skinSignature = prop.getSignature();
                break;
            }
        }

        // Extract connection information
        String ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
        String hostname = player.getVirtualHost()
                .map(java.net.InetSocketAddress::getHostString)
                .orElse("");
        int protocolVersion = player.getProtocolVersion().getProtocol();
        String locale = player.getPlayerSettings().getLocale().toString();
        long connectionTimestamp = System.currentTimeMillis();

        // Build login request with all available data
        var loginRequestBuilder = LoginRequest.newBuilder()
                .setUniqueId(player.getUniqueId().toString())
                .setUsername(player.getUsername())
                .setProxyName(PROXY_NAME)
                .setIpAddress(ipAddress)
                .setProtocolVersion(protocolVersion)
                .setLocale(locale)
                .setConnectionTimestamp(connectionTimestamp);

        // Add optional fields
        if (skinTexture != null) {
            loginRequestBuilder.setSkinTexture(skinTexture);
        }
        if (skinSignature != null) {
            loginRequestBuilder.setSkinSignature(skinSignature);
        }
        if (!hostname.isEmpty()) {
            loginRequestBuilder.setHostname(hostname);
        }

        // Note: client_brand and minecraft_version require additional packet listening
        // These would need to be populated after the player joins if available

        var future = super.playerServiceStub.login(loginRequestBuilder.build());
        return createCallback(future, (response) -> PlayerMapper.toLocal(response.getPlayer(), getAfkServiceApi()));
    }

    public CompletableFuture<Boolean> handleLogout(Player player) {
        var future = super.playerServiceStub.logout(LogoutRequest.newBuilder().setUniqueId(player.getUniqueId().toString()).build());
        return createCallback(future, LogoutNotify::getSuccess);
    }


    public CompletableFuture<Boolean> handleServerChange(Player player) {
        String serverName = player.getCurrentServer().map(ServerConnection::getServerInfo).map(ServerInfo::getName).orElse(null);
        var future = super.playerServiceStub.updateConnection(UpdateConnectionRequest.newBuilder()
                .setUniqueId(player.getUniqueId().toString())
                .setConnectedServerName(serverName)
                .build());
        return createCallback(future, UpdateConnectionResponse::getSuccess);
    }

    @Override
    public void connectPlayerToServer(UUID uuid, String serviceName) {
        RegisteredServer server = lookupServer(serviceName);
        if (server == null) return;
        Player player = proxyServer.getPlayer(uuid).orElse(null);
        if (player == null) return;
        player.createConnectionRequest(server).fireAndForget();
    }

    @Override
    public CompletableFuture<ServerConnectResult> connectPlayer(UUID uuid, String serviceName) {
        RegisteredServer server = lookupServer(serviceName);
        if (server == null) {
            return CompletableFuture.completedFuture(ServerConnectResult.SERVER_NOT_FOUND);
        }
        Player player = proxyServer.getPlayer(uuid).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(ServerConnectResult.PLAYER_NOT_FOUND);
        }
        return player.createConnectionRequest(server).connect().thenApply(result -> {
            if (result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                return ServerConnectResult.SUCCESS;
            } else {
                return ServerConnectResult.ERROR;
            }
        });
    }

    /**
     * Passed to the redis handler
     *
     * @param connectRequest request, received on pub sub
     */
    @Override
    public void accept(ConnectRequest connectRequest) {
        UUID uuid = UUID.fromString(connectRequest.getUniqueId());
        String name = connectRequest.getServerName();
        connectPlayer(uuid, name)
                .thenAccept(result -> respondIfRequired(connectRequest, result))
                .exceptionally(ex -> {
                    respondIfRequired(connectRequest, ServerConnectResult.ERROR);
                    log.error("Failed to handle connect request for player {}", uuid, ex);
                    return null;
                });
    }

    /**
     * If a request expects a response, it is sent back.
     *
     * @param req    the request
     * @param result the connection result
     */
    private void respondIfRequired(ConnectRequest req, @NotNull ServerConnectResult result) {
        if (!req.hasResponseKey()) {
            return;
        }
        ConnectResult protoBufResult = switch (result) {
            case SUCCESS -> ConnectResult.SUCCESS;
            case PLAYER_NOT_FOUND -> ConnectResult.PLAYER_NOT_FOUND;
            case SERVER_NOT_FOUND -> ConnectResult.SERVER_NOT_FOUND;
            case ERROR -> ConnectResult.CONNECTION_ERROR;
        };
        ConnectResponse resp = ConnectResponse.newBuilder().setResponseKey(req.getResponseKey()).setResult(protoBufResult).build();
        var conn = getRedisPubSubHandler().getConnection();
        Preconditions.checkNotNull(conn, "Redis must not be null on response");
        conn.async().publish(RedisPubSubHandler.CONNECT_RES_CHANNEL, resp.toByteArray());
    }

    /**
     * If the server name contains a dash, it is considered a full server name, otherwise it's considered a prefix and the first result gets picked.
     *
     * @param serviceName the name of the server to connect to.
     * @return the server if found, null otherwise.
     */
    private @Nullable RegisteredServer lookupServer(String serviceName) {
        RegisteredServer server;
        if (serviceName.contains("-")) {
            server = proxyServer.getServer(serviceName).orElse(null);
        } else {
            server = null;
            for (var s : proxyServer.getAllServers()) {
                if (s.getServerInfo().getName().startsWith(serviceName)) {
                    server = s;
                    break;
                }
            }
        }
        return server;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }
}
