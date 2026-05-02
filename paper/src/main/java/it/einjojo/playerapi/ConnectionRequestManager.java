package it.einjojo.playerapi;

import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import it.einjojo.playerapi.util.DistributedIdGenerator;
import it.einjojo.protocol.player.ConnectRequest;
import it.einjojo.protocol.player.ConnectResponse;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages connection requests to move players between servers via Redis pub/sub.
 * <p>
 * Supports both fire-and-forget requests and requests that expect a response.
 * Uses Redis pub/sub to communicate with the proxy (Velocity).
 * <p>
 * Thread-safe: All operations are safe for concurrent access.
 * <p>
 * <b>Distributed Deployment:</b> This class uses {@link DistributedIdGenerator} to generate
 * unique response keys across multiple backend servers using timestamp-based server IDs.
 * No configuration required - server IDs are automatically derived from startup timestamp.
 */
@NullMarked
public class ConnectionRequestManager implements Consumer<ConnectResponse>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRequestManager.class);
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    private static final long CLEANUP_INTERVAL_SECONDS = 30;

    private final DistributedIdGenerator idGenerator;
    private final String replyChannel;
    private final StatefulRedisPubSubConnection<byte[], byte[]> connection;
    private final ConcurrentHashMap<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * Creates a new ConnectionRequestManager.
     *
     * @param pubSubHandler the Redis pub/sub handler to use for communication
     * @throws NullPointerException if pubSubHandler is null
     */
    public ConnectionRequestManager(RedisPubSubHandler pubSubHandler) {
        Objects.requireNonNull(pubSubHandler, "pubSubHandler must not be null");

        // Initialize distributed ID generator
        this.idGenerator = new DistributedIdGenerator();

        pubSubHandler.setConnectResponseConsumer(this);
        this.replyChannel = pubSubHandler.getInstanceReplyChannelName();
        this.connection = pubSubHandler.getOpenConnection();

        // Schedule periodic cleanup of expired requests
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConnectionRequestManager-Cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredRequests,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("ConnectionRequestManager initialized (server_id={}, reply_channel={}, cleanup_interval={}s)",
                idGenerator.getServerId(), replyChannel, CLEANUP_INTERVAL_SECONDS);
    }

    /**
     * Callback invoked when a connection response is received from the proxy.
     *
     * @param connectResponse the response to a connection request
     */
    @Override
    public void accept(ConnectResponse connectResponse) {
        int responseKey = connectResponse.getResponseKey();
        PendingRequest pendingRequest = pendingRequests.remove(responseKey);

        if (pendingRequest == null) {
            log.warn("Received response for unknown request key {} - request may have timed out or was never sent",
                    responseKey);
            return;
        }

        ServerConnectResult result = switch (connectResponse.getResult()) {
            case SUCCESS -> ServerConnectResult.SUCCESS;
            case PLAYER_NOT_FOUND -> ServerConnectResult.PLAYER_NOT_FOUND;
            case SERVER_NOT_FOUND -> ServerConnectResult.SERVER_NOT_FOUND;
            case CONNECTION_ERROR, UNRECOGNIZED -> ServerConnectResult.ERROR;
        };

        pendingRequest.future.complete(result);
        log.debug("Completed connection request {} with result {}", responseKey, result);
    }

    /**
     * Send a connection request without expecting a response (fire-and-forget).
     * Use this when you don't care about the result.
     *
     * @param uuid       player UUID to connect
     * @param serverName target server name
     */
    public void sendFireAndForget(UUID uuid, String serverName) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(serverName, "serverName must not be null");

        ConnectRequest request = ConnectRequest.newBuilder()
                .setUniqueId(uuid.toString())
                .setServerName(serverName)
                .build();

        // Async publish - non-blocking
        connection.async()
                .publish(RedisPubSubHandler.CONNECT_REQ_CHANNEL, request.toByteArray())
                .whenComplete((published, error) -> {
                    if (error != null) {
                        log.error("Failed to publish fire-and-forget connection request for player {} to {}",
                                uuid, serverName, error);
                    } else {
                        log.debug("Published fire-and-forget connection request for player {} to {}",
                                uuid, serverName);
                    }
                });
    }

    /**
     * Send a connection request and wait for a response.
     * The returned future will complete when the proxy responds or after a timeout.
     *
     * @param uuid       player UUID to connect
     * @param serverName target server name
     * @return a future that completes with the connection result
     */
    public CompletableFuture<ServerConnectResult> sendWithResponse(UUID uuid, String serverName) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(serverName, "serverName must not be null");

        // Generate unique response key (collision-proof with atomic increment)
        int responseKey = generateResponseKey();

        CompletableFuture<ServerConnectResult> future = new CompletableFuture<>();
        long expiryTime = System.currentTimeMillis() + (REQUEST_TIMEOUT_SECONDS * 1000);

        // Register pending request before sending
        pendingRequests.put(responseKey, new PendingRequest(responseKey, future, expiryTime));

        ConnectRequest request = ConnectRequest.newBuilder()
                .setUniqueId(uuid.toString())
                .setServerName(serverName)
                .setResponseKey(responseKey)
                .setReplyChannel(replyChannel)
                .build();

        // Async publish - non-blocking
        connection.async()
                .publish(RedisPubSubHandler.CONNECT_REQ_CHANNEL, request.toByteArray())
                .whenComplete((published, error) -> {
                    if (error != null) {
                        log.error("Failed to publish connection request {} for player {} to {}",
                                responseKey, uuid, serverName, error);
                        pendingRequests.remove(responseKey);
                        future.completeExceptionally(error);
                    } else {
                        log.debug("Published connection request {} for player {} to {}",
                                responseKey, uuid, serverName);
                    }
                });

        // Apply timeout and cleanup
        return future
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, error) -> {
                    // Always clean up on completion (success, error, or timeout)
                    pendingRequests.remove(responseKey);

                    if (error != null) {
                        if (error instanceof TimeoutException) {
                            log.warn("Connection request {} for player {} to {} timed out after {}s",
                                    responseKey, uuid, serverName, REQUEST_TIMEOUT_SECONDS);
                        } else {
                            log.error("Connection request {} for player {} to {} failed",
                                    responseKey, uuid, serverName, error);
                        }
                    }
                });
    }

    /**
     * Generate a globally unique response key for distributed deployments.
     * Delegates to {@link DistributedIdGenerator} for collision-free ID generation.
     *
     * @return a unique response key across all servers
     */
    private int generateResponseKey() {
        return idGenerator.nextId();
    }

    /**
     * Periodically clean up expired pending requests.
     * Called by scheduled executor.
     */
    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        int removedCount = 0;

        for (var entry : pendingRequests.entrySet()) {
            if (entry.getValue().isExpired(now)) {
                if (pendingRequests.remove(entry.getKey()) != null) {
                    // Complete the future with timeout exception
                    entry.getValue().future.completeExceptionally(
                            new TimeoutException("Connection request expired during cleanup")
                    );
                    removedCount++;
                }
            }
        }

        if (removedCount > 0) {
            log.info("Cleaned up {} expired connection requests", removedCount);
        }
    }

    /**
     * Get the number of pending requests (for monitoring/debugging).
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    /**
     * Close this manager and release resources.
     */
    @Override
    public void close() {
        log.info("Shutting down ConnectionRequestManager...");

        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Complete all pending requests with cancellation exception
        int cancelledCount = 0;
        for (var request : pendingRequests.values()) {
            if (request.future.completeExceptionally(
                    new CancellationException("ConnectionRequestManager closed"))) {
                cancelledCount++;
            }
        }
        pendingRequests.clear();

        log.info("ConnectionRequestManager closed ({} requests cancelled)", cancelledCount);
    }

    /**
     * Represents a pending connection request waiting for a response.
     */
    private record PendingRequest(
            int responseKey,
            CompletableFuture<ServerConnectResult> future,
            long expiryTime) {

        public boolean isExpired(long now) {
            return expiryTime < now;
        }
    }
}
