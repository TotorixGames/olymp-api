package it.einjojo.playerapi;

import io.lettuce.core.api.StatefulRedisConnection;
import it.einjojo.protocol.player.ConnectRequest;
import it.einjojo.protocol.player.ConnectResponse;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@NullMarked
public class ConnectionRequestManager implements Consumer<ConnectResponse> {
    private static final SecureRandom random = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(ConnectionRequestManager.class);
    private final StatefulRedisConnection<byte[], byte[]> connection;
    private final LinkedBlockingDeque<PendingRequest> pendingRequests = new LinkedBlockingDeque<>();


    public ConnectionRequestManager(RedisPubSubHandler pubSubHandler) {
        pubSubHandler.setConnectResponseConsumer(this);
        // Use getOpenConnection() to ensure connection is initialized
        this.connection = pubSubHandler.getOpenConnection();
    }

    /**
     * Callback invoked when a connection response is received.
     *
     * @param connectResponse the response to a connection request
     */
    @Override
    public void accept(ConnectResponse connectResponse) {
        var future = pop(connectResponse.getResponseKey());
        if (future == null) {
            log.warn("No future found for response key {}", connectResponse.getResponseKey());
            return;
        }

        ServerConnectResult res = switch (connectResponse.getResult()) {
            case SUCCESS -> ServerConnectResult.SUCCESS;
            case PLAYER_NOT_FOUND -> ServerConnectResult.PLAYER_NOT_FOUND;
            case SERVER_NOT_FOUND -> ServerConnectResult.SERVER_NOT_FOUND;
            case CONNECTION_ERROR, UNRECOGNIZED -> ServerConnectResult.ERROR;
        };
        future.complete(res);
    }

    /**
     * Looks into the pending requests and removes the element associated with the response key.
     * On the iteration all expired elements are removed.
     *
     * @param responseKey the response to a connection request
     * @return the removed element, or null if not found
     */
    private @Nullable CompletableFuture<ServerConnectResult> pop(int responseKey) {
        for (var it = pendingRequests.iterator(); it.hasNext(); ) {
            var pendingRequest = it.next();
            if (pendingRequest.isExpired()) {
                it.remove();
                continue;
            }
            if (pendingRequest.responseKey == responseKey) {
                it.remove();
                return pendingRequest.future;
            }
        }
        return null;
    }

    public void fireAndForget(UUID uuid, String serviceName) {
        connection.sync().publish(RedisPubSubHandler.CONNECT_REQ_CHANNEL, ConnectRequest.newBuilder()
                .setUniqueId(uuid.toString())
                .setServerName(serviceName)
                .build().toByteArray());
    }

    public CompletableFuture<ServerConnectResult> newRequest(UUID uuid, String serviceName) {
        int responseKey = random.nextInt(Integer.MAX_VALUE);
        CompletableFuture<ServerConnectResult> future = new CompletableFuture<>();
        pendingRequests.add(new PendingRequest(responseKey, future, System.currentTimeMillis() + 10_000));
        connection.sync().publish(RedisPubSubHandler.CONNECT_REQ_CHANNEL, ConnectRequest.newBuilder()
                .setUniqueId(uuid.toString())
                .setServerName(serviceName)
                .setResponseKey(responseKey)
                .build().toByteArray());
        // Ensure we remove the matching PendingRequest (by responseKey) when the future completes
        return future.orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> pendingRequests.removeIf(p -> p.responseKey == responseKey));


    }

    private record PendingRequest(int responseKey, CompletableFuture<ServerConnectResult> future, long expiryTime) {
        public boolean isExpired() {
            return expiryTime < System.currentTimeMillis();
        }

    }
}
