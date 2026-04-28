package it.einjojo.playerapi;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a player that is currently online on the network.
 */
public interface NetworkPlayer extends OfflineNetworkPlayer {

    /**
     * Returns the name of the game server the player is currently connected to.
     *
     * @return an {@link Optional} containing the server name, or empty if unknown
     */
    Optional<String> getConnectedServerName();

    /**
     * Returns the name of the proxy the player is connected through.
     *
     * @return the proxy name; never {@code null}
     */
    String getConnectedProxyName();

    /**
     * Returns the elapsed time since the player's session started.
     *
     * @return session duration in milliseconds
     */
    default long getSessionTime() {
        return System.currentTimeMillis() - getLastPlayed();
    }

    /**
     * Returns the unique identifier for the player's current session.
     *
     * @return the session id
     */
    long getSessionId();

    /**
     * Checks whether this player is currently marked as AFK (Away From Keyboard).
     *
     * <p>The result is fetched lazily from the AFK service on each invocation.
     *
     * @return a future completing with {@code true} if the player is AFK, {@code false} otherwise;
     * completes with {@code null} if the player was not found remotely
     */
    CompletableFuture<Boolean> isAfk();

    /**
     * Sets the AFK status of this player.
     *
     * <p>The update is applied remotely via the AFK service.
     *
     * @param afk {@code true} to mark the player as AFK, {@code false} to clear AFK status
     * @return a future completing when the operation has been acknowledged by the service,
     * or completing exceptionally on failure
     */
    CompletableFuture<Void> setAfk(boolean afk);

    /**
     * Gets the state of this object upon object creation
     *
     * @return true / false
     */
    boolean isAfkSnapshot();
}
