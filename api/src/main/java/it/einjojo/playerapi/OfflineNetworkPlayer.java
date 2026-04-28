package it.einjojo.playerapi;

import java.util.UUID;

/**
 * An offline player has connected at least once to the server.
 */
public interface OfflineNetworkPlayer {


    UUID getUniqueId();

    String getName();

    boolean isOnline();

    /**
     * timestamp
     *
     * @return milliseconds since 1970-01-01
     */
    long getFirstPlayed();

    /**
     * Timestamp
     *
     * @return milliseconds since 1970-01-01
     */
    long getLastPlayed();

    /**
     * Playtime
     *
     * @return accumulated playtime in milliseconds.
     * <p>
     * Does not contain the current session time.
     * @see NetworkPlayer#getSessionTime()
     */
    long getPlaytime();

    /**
     * always up-to-date duration millis.
     *
     * @return the duration
     */
    long getAfkDuration();

}
