package it.einjojo.playerapi;

import java.util.Collection;
import java.util.UUID;

/**
 * Accesses the implementation's (player / velocity) player-container and can determine whether a player is online locally or not.
 */
public interface LocalOnlinePlayerAccessor {

    /**
     * checks whether a player is online or not.
     *
     * @param uuid the player's UUID
     * @return true if the player is online, false otherwise
     */
    boolean isOnline(UUID uuid);

    boolean isOnline(String name);

    /**
     * Get all names of local online players.
     *
     * @return a collection of player names.
     */
    Collection<String> getPlayerNames();



}
