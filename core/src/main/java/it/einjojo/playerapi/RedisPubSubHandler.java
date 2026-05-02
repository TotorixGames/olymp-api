package it.einjojo.playerapi;

import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import it.einjojo.playerapi.config.RedisConnectionConfiguration;
import it.einjojo.protocol.player.ConnectRequest;
import it.einjojo.protocol.player.ConnectResponse;
import it.einjojo.protocol.player.LoginNotify;
import it.einjojo.protocol.player.LogoutNotify;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * @author EinjoJo
 * <p>Lazy redis handler. Stays inactive as long as no consumers are registered.</p>
 * <p>Listens to playerapi:login and playerapi:logout channels and dispatch the received protobuf messages to the provided consumers.</p>
 * <p>This class is thread-safe. Multiple consumers can be registered for login/logout notifications.</p>
 * <p>Note: Connect request/response channels support only a single consumer - calling set methods multiple times will overwrite the previous consumer.</p>
 */
public class RedisPubSubHandler extends RedisPubSubAdapter<byte[], byte[]> implements Closeable {
    protected static final byte[] LOGIN_NOTIFY_CHANNEL = "plapi:li".getBytes(StandardCharsets.UTF_8);
    protected static final byte[] LOGOUT_NOTIFY_CHANNEL = "plapi:lo".getBytes(StandardCharsets.UTF_8);
    protected static final byte[] CONNECT_REQ_CHANNEL = "plapi:co".getBytes(StandardCharsets.UTF_8);
    protected static final byte[] CONNECT_RES_CHANNEL = "plapi:rco".getBytes(StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(RedisPubSubHandler.class);
    private final RedisURI redisUri;
    private final Executor executor;
    // Unique per-instance channel so only this server receives its own ConnectResponses
    private final byte[] instanceReplyChannel;
    private @Nullable CopyOnWriteArrayList<Consumer<LoginNotify>> loginNotifyConsumers;
    private @Nullable CopyOnWriteArrayList<Consumer<LogoutNotify>> logoutNotifyConsumers;
    private @Nullable Consumer<ConnectRequest> connectRequestConsumer;
    private @Nullable Consumer<ConnectResponse> connectResponseConsumer;
    private @Nullable RedisClient client;
    private @Nullable StatefulRedisPubSubConnection<byte[], byte[]> connection;
    private final Object connectionLock = new Object();
    private final Object loginConsumerLock = new Object();
    private final Object logoutConsumerLock = new Object();


    /**
     * Constructor for RedisPubSubHandler.
     *
     * @param redisConnectionConfiguration config
     * @param executor                     avoid blocking Event-Loop in Pub Sub Listener
     */
    public RedisPubSubHandler(@NotNull RedisConnectionConfiguration redisConnectionConfiguration, @NotNull Executor executor) {
        this.redisUri = redisConnectionConfiguration.createUri("playerapi");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.instanceReplyChannel = ("plapi:rco:" + UUID.randomUUID().toString().replace("-", ""))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the unique Redis channel name this instance subscribes to for ConnectResponses. */
    public String getInstanceReplyChannelName() {
        return new String(instanceReplyChannel, StandardCharsets.UTF_8);
    }

    /**
     * Subscribe to login notifications. Multiple consumers can be registered.
     * The returned Closeable can be used to unsubscribe this specific consumer.
     * When the last consumer is removed, the channel subscription is automatically cancelled.
     *
     * @param consumer the consumer to receive login notifications
     * @return a Closeable to unsubscribe this consumer
     */
    public Closeable subscribeLogin(@NotNull Consumer<LoginNotify> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");

        synchronized (loginConsumerLock) {
            if (loginNotifyConsumers == null) {
                loginNotifyConsumers = new CopyOnWriteArrayList<>();
                var connection = getOpenConnection();
                connection.sync().subscribe(LOGIN_NOTIFY_CHANNEL);
                log.info("Subscribed to login notify channel");
            }
            loginNotifyConsumers.add(consumer);
            log.info("Registered login notify consumer (total: {})", loginNotifyConsumers.size());
        }

        return () -> {
            synchronized (loginConsumerLock) {
                if (loginNotifyConsumers != null) {
                    loginNotifyConsumers.remove(consumer);
                    log.info("Removed login notify consumer (remaining: {})", loginNotifyConsumers.size());

                    if (loginNotifyConsumers.isEmpty()) {
                        var conn = connection;
                        if (conn != null && conn.isOpen()) {
                            conn.sync().unsubscribe(LOGIN_NOTIFY_CHANNEL);
                            log.info("Unsubscribed from login notify channel (no consumers left)");
                        }
                        loginNotifyConsumers = null;
                    }
                }
            }
        };
    }

    /**
     * Subscribe to logout notifications. Multiple consumers can be registered.
     * The returned Closeable can be used to unsubscribe this specific consumer.
     * When the last consumer is removed, the channel subscription is automatically cancelled.
     *
     * @param consumer the consumer to receive logout notifications
     * @return a Closeable to unsubscribe this consumer
     */
    public Closeable subscribeLogout(@NotNull Consumer<LogoutNotify> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");

        synchronized (logoutConsumerLock) {
            if (logoutNotifyConsumers == null) {
                logoutNotifyConsumers = new CopyOnWriteArrayList<>();
                var connection = getOpenConnection();
                connection.sync().subscribe(LOGOUT_NOTIFY_CHANNEL);
                log.info("Subscribed to logout notify channel");
            }
            logoutNotifyConsumers.add(consumer);
            log.info("Registered logout notify consumer (total: {})", logoutNotifyConsumers.size());
        }

        return () -> {
            synchronized (logoutConsumerLock) {
                if (logoutNotifyConsumers != null) {
                    logoutNotifyConsumers.remove(consumer);
                    log.info("Removed logout notify consumer (remaining: {})", logoutNotifyConsumers.size());

                    if (logoutNotifyConsumers.isEmpty()) {
                        var conn = connection;
                        if (conn != null && conn.isOpen()) {
                            conn.sync().unsubscribe(LOGOUT_NOTIFY_CHANNEL);
                            log.info("Unsubscribed from logout notify channel (no consumers left)");
                        }
                        logoutNotifyConsumers = null;
                    }
                }
            }
        };
    }


    /**
     * Set the consumer for connect requests. Only ONE consumer is supported.
     * Calling this method multiple times will OVERWRITE the previous consumer.
     *
     * @param consumer the consumer to receive connect requests
     */
    @ApiStatus.Internal
    protected void setConnectRequestConsumer(@NotNull Consumer<ConnectRequest> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        var connection = getOpenConnection();
        if (connectRequestConsumer == null) {
            connection.sync().subscribe(CONNECT_REQ_CHANNEL);
            log.info("Subscribed to connect request channel");
        } else {
            log.warn("Overwriting existing connect request consumer");
        }
        connectRequestConsumer = consumer;
    }


    /**
     * Set the consumer for connect responses. Only ONE consumer is supported.
     * Calling this method multiple times will OVERWRITE the previous consumer.
     *
     * @param consumer the consumer to receive connect responses
     */
    @ApiStatus.Internal
    protected void setConnectResponseConsumer(@NotNull Consumer<ConnectResponse> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        var connection = getOpenConnection();
        if (connectResponseConsumer == null) {
            connection.sync().subscribe(instanceReplyChannel);
            log.info("Subscribed to per-instance connect response channel: {}", getInstanceReplyChannelName());
        } else {
            log.warn("Overwriting existing connect response consumer");
        }
        connectResponseConsumer = consumer;
    }


    /**
     * Get or lazily create the Redis pub/sub connection.
     * This method is thread-safe and uses double-checked locking.
     * The connection is configured with auto-reconnect enabled.
     *
     * @return the active pub/sub connection
     * @throws io.lettuce.core.RedisConnectionException if connection fails
     */
    public @NotNull StatefulRedisPubSubConnection<byte[], byte[]> getOpenConnection() {
        StatefulRedisPubSubConnection<byte[], byte[]> conn = connection;
        if (conn != null && conn.isOpen()) {
            return conn;
        }

        synchronized (connectionLock) {
            conn = connection;
            if (conn == null || !conn.isOpen()) {
                if (client == null) {
                    client = RedisClient.create(redisUri);

                    // Configure client with best practices
                    ClientOptions options = ClientOptions.builder()
                            .autoReconnect(true)
                            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                            .build();
                    client.setOptions(options);

                    log.info("Created redis client with auto-reconnect enabled");
                }
                conn = client.connectPubSub(ByteArrayCodec.INSTANCE);
                conn.addListener(this);
                connection = conn;
                log.info("Opened connection to redis pub sub");
            }
            return conn;
        }
    }


    @Override
    public void message(byte[] channel, byte[] message) {
        // pass to executor to avoid any unpurposed blocking calls inside this Pub/Sub callback
        if (loginNotifyConsumers != null && Arrays.equals(channel, LOGIN_NOTIFY_CHANNEL)) {
            try {
                LoginNotify notify = LoginNotify.parseFrom(message);
                // capture the list reference to avoid races where the field is cleared while the runnable is queued
                var consumers = loginNotifyConsumers;
                if (consumers == null) return;
                executor.execute(() -> {
                    for (Consumer<LoginNotify> consumer : consumers) {
                        try {
                            consumer.accept(notify);
                        } catch (Exception e) {
                            log.error("Exception during login notify consumer processing", e);
                        }
                    }
                });
            } catch (InvalidProtocolBufferException e) {
                log.error("Failed to parse LoginNotify message", e);
            }
        } else if (logoutNotifyConsumers != null && Arrays.equals(channel, LOGOUT_NOTIFY_CHANNEL)) {
            try {
                LogoutNotify logoutNotify = LogoutNotify.parseFrom(message);
                var consumers = logoutNotifyConsumers;
                if (consumers == null) return;
                executor.execute(() -> {
                    for (Consumer<LogoutNotify> consumer : consumers) {
                        try {
                            consumer.accept(logoutNotify);
                        } catch (Exception e) {
                            log.error("Exception during logout notify consumer processing", e);
                        }
                    }
                });
            } catch (InvalidProtocolBufferException e) {
                log.error("Failed to parse LogoutNotify message", e);
            }
        } else if (connectRequestConsumer != null && Arrays.equals(channel, CONNECT_REQ_CHANNEL)) {
            try {
                ConnectRequest connectRequest = ConnectRequest.parseFrom(message);
                var consumer = connectRequestConsumer;
                if (consumer == null) return;
                executor.execute(() -> {
                    try {
                        consumer.accept(connectRequest);
                    } catch (Exception ex) {
                        log.error("Exception during connect request consumer processing", ex);
                    }
                });
            } catch (InvalidProtocolBufferException e) {
                log.error("Failed to parse ConnectRequest message", e);
            }
        } else if (connectResponseConsumer != null && Arrays.equals(channel, instanceReplyChannel)) {
            try {
                ConnectResponse connectResponse = ConnectResponse.parseFrom(message);
                var consumer = connectResponseConsumer;
                if (consumer == null) return;
                executor.execute(() -> {
                    try {
                        consumer.accept(connectResponse);
                    } catch (Exception e) {
                        log.error("Exception during connect response consumer processing", e);
                    }
                });
            } catch (InvalidProtocolBufferException e) {
                log.error("Failed to parse ConnectResponse message", e);
            }
        }
    }

    public @Nullable RedisClient getClient() {
        return client;
    }

    public @Nullable StatefulRedisPubSubConnection<byte[], byte[]> getConnection() {
        return connection;
    }

    /**
     * Close the Redis connection and client.
     * This method is idempotent and thread-safe.
     * It will unsubscribe from all channels and shutdown the client gracefully.
     */
    @Override
    public void close() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    // Unsubscribe from all channels before closing
                    if (connection.isOpen()) {
                        try {
                            connection.sync().unsubscribe();
                            log.info("Unsubscribed from all channels");
                        } catch (Exception e) {
                            log.warn("Error during unsubscribe", e);
                        }
                    }
                    connection.close();
                    log.info("Closed Redis pub/sub connection");
                } catch (Exception e) {
                    log.error("Error closing connection", e);
                } finally {
                    connection = null;
                }
            }

            if (client != null) {
                try {
                    client.shutdown();
                    log.info("Shutdown Redis client");
                } catch (Exception e) {
                    log.error("Error shutting down client", e);
                } finally {
                    client = null;
                }
            }
        }

        // Clear consumer lists
        synchronized (loginConsumerLock) {
            loginNotifyConsumers = null;
        }
        synchronized (logoutConsumerLock) {
            logoutNotifyConsumers = null;
        }
        connectRequestConsumer = null;
        connectResponseConsumer = null;
    }
}
