package it.einjojo.playerapi;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service API for managing AFK (Away From Keyboard) status of online players.
 *
 * <p>All operations are non-blocking and return {@link CompletableFuture} that complete
 * on the executor configured for the underlying gRPC channel.
 */
public interface AfkServiceApi {

    /**
     * Retrieves the current AFK status of the specified player.
     *
     * @param playerUUID the unique identifier of the player; must not be {@code null}
     * @return a future completing with {@code true} if the player is AFK, {@code false} if not,
     *         or {@code null} if the player was not found; completes exceptionally on transport error
     */
    CompletableFuture<Boolean> isAfk(UUID playerUUID);

    /**
     * Sets the AFK status of the specified player.
     *
     * @param playerUUID the unique identifier of the player; must not be {@code null}
     * @param afk        {@code true} to mark the player as AFK, {@code false} to clear AFK status
     * @return a future completing with {@code null} when the operation succeeds,
     *         or completing exceptionally on failure
     */
    CompletableFuture<Void> setAfk(UUID playerUUID, boolean afk);
}
