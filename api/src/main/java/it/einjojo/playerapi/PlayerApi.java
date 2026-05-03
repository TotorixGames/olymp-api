package it.einjojo.playerapi;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * PlayerAPI can be obtained by {@link PlayerApiProvider} on any platform.
 */
public interface PlayerApi {

    /**
     * Gets a list of all online players.
     *
     * @return a list of online players.
     */
    CompletableFuture<List<NetworkPlayer>> getOnlinePlayers();

    /**
     * Gets the names of all online players.
     *
     * @return a list of player names.
     * @deprecated in favor for #tabCompelte
     */
    @Deprecated
    CompletableFuture<List<String>> getOnlinePlayerNames();


    /**
     * Fuzzy search Tab Complete by backend with 12 results
     *
     * @param input     what the player has typed in
     * @param completer uuid of player or null
     */
    default CompletableFuture<List<String>> tabCompleteOnlinePlayers(String input, UUID completer) {
        return tabCompleteOnlinePlayers(input, completer, 12);
    }

    /**
     * Fuzzy search Tab Complete by backend with 12 results
     *
     * @param input     what the player has typed in
     * @param completer uuid of player or null
     */
    default CompletableFuture<List<String>> tabCompleteOfflinePlayers(String input, UUID completer) {
        return tabCompleteOnlinePlayers(input, completer, 12);
    }

    /**
     * Fuzzy search Tab Complete by backend
     *
     * @param input     what the player has typed in
     * @param completer uuid of player or null
     * @param results   max response size
     */
    CompletableFuture<List<String>> tabCompleteOnlinePlayers(String input, UUID completer, int results);

    /**
     * Fuzzy search Tab Complete by backend
     *
     * @param input     what the player has typed in
     * @param completer uuid of player or null
     * @param results   max response size
     */
    CompletableFuture<List<String>> tabCompleteOfflinePlayers(String input, UUID completer, int results);

    /**
     * Connects a player to a server.
     *
     * @param uuid        the player's UUID
     * @param serviceName the name of the server to connect to. If the string does not contain a separator ('-'), it connects to a random server of the group.
     * @return a future
     * @since 1.5
     */
    CompletableFuture<ServerConnectResult> connectPlayer(UUID uuid, String serviceName);

    /**
     * Fire and forget legacy method. Exceptions are swallowed
     *
     * @param uuid        player
     * @param serviceName server
     * @see #connectPlayer(UUID, String)
     */
    default void connectPlayerToServer(UUID uuid, String serviceName) {
        connectPlayer(uuid, serviceName);
    }

    CompletableFuture<@Nullable OfflineNetworkPlayer> getOfflinePlayer(String playerName);

    CompletableFuture<@Nullable OfflineNetworkPlayer> getOfflinePlayer(UUID playerUUID);

    CompletableFuture<Boolean> isPlayerOnline(String playerName);

    CompletableFuture<Boolean> isPlayerOnline(UUID playerUUID);

    CompletableFuture<@Nullable NetworkPlayer> getOnlinePlayer(String playerName);

    CompletableFuture<@Nullable NetworkPlayer> getOnlinePlayer(UUID playerUUID);

    CompletableFuture<@Nullable UUID> getUniqueId(String playerName);

    LocalOnlinePlayerAccessor getLocalOnlinePlayerAccessor();


    /**
     * Subscribe to all player logins
     *
     * @param playerConsumer the consumer that will be called when a player logs in.
     * @return a {@link Closeable} that can be used to unsubscribe from the event.
     */
    Closeable subscribeLogin(Consumer<NetworkPlayer> playerConsumer);

    /**
     * Subscribe to all player logouts
     *
     * @param offlinePlayerConsumer the consumer that will be called when a player logs out.
     * @return a {@link Closeable} that can be used to unsubscribe from the event.
     */
    Closeable subscribeLogout(Consumer<OfflineNetworkPlayer> offlinePlayerConsumer);

}
