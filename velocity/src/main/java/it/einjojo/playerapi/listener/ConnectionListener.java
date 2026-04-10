package it.einjojo.playerapi.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import it.einjojo.playerapi.NetworkPlayer;
import it.einjojo.playerapi.VelocityPlayerApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles player connection lifecycle events for the PlayerAPI.
 * <p>
 * <b>Critical Flow:</b>
 * 1. LoginEvent - Register player with PlayerAPI (BLOCKS until complete or fails)
 * 2. ServerPostConnectEvent - Update player's connected server
 * 3. DisconnectEvent - Handle player logout
 * <p>
 * The player MUST be registered in PlayerAPI before connecting to backend servers,
 * otherwise backend servers cannot verify the session.
 */
public class ConnectionListener {
    private static final int LOGIN_TIMEOUT_SECONDS = 10;
    private static final Component VERIFYING_MESSAGE = Component.text()
            .content("Verifying session with PlayerAPI...")
            .color(NamedTextColor.YELLOW)
            .build();

    private final VelocityPlayerApi playerApi;
    private final Logger logger;

    public ConnectionListener(VelocityPlayerApi playerApi, Logger logger) {
        this.playerApi = playerApi;
        this.logger = logger;
    }

    /**
     * Handle player login to the proxy.
     * <p>
     * This event fires AFTER Mojang authentication but BEFORE the player can connect to backend servers.
     * We MUST complete PlayerAPI registration here to ensure backend servers can verify the session.
     * <p>
     * Using EARLY order to process before other plugins that might depend on the player being registered.
     * <p>
     * <b>IMPORTANT:</b> This method blocks the login process until PlayerAPI registration completes.
     * The player will see "Verifying session..." until the API call succeeds or times out.
     */
    @Subscribe(async = false)
    public void handleLogin(LoginEvent event, Continuation continuation) {
        var player = event.getPlayer();
        String username = player.getUsername();
        UUID playerId = player.getUniqueId();


        logger.info("Registering player {} ({}) with PlayerAPI...", username, playerId);

        // Initially deny with "verifying" message
        event.setResult(LoginEvent.ComponentResult.denied(VERIFYING_MESSAGE));

        // Perform async PlayerAPI registration with timeout
        playerApi.handleLogin(player)
                .orTimeout(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((networkPlayer, error) -> {
                    if (error != null) {
                        // Login failed - deny with appropriate error message
                        handleLoginFailure(event, username, playerId, error);
                        continuation.resume();
                    } else {
                        // Login succeeded - allow player to proceed
                        handleLoginSuccess(event, username, networkPlayer);
                        continuation.resume();
                    }
                });
    }

    /**
     * Handle successful PlayerAPI registration.
     */
    private void handleLoginSuccess(LoginEvent event, String username, NetworkPlayer networkPlayer) {
        logger.info("Successfully registered player {} with PlayerAPI (session: {})",
                username, networkPlayer.getSessionId());

        // Allow the player to proceed to server selection
        event.setResult(LoginEvent.ComponentResult.allowed());

    }

    /**
     * Handle failed PlayerAPI registration.
     * Generates a reference ID and denies login with an appropriate error message.
     */
    private void handleLoginFailure(LoginEvent event, String username, UUID playerId, Throwable error) {
        String refId = UUID.randomUUID().toString().substring(0, 8);

        // Unwrap completion exceptions
        Throwable cause = error instanceof CompletionException ? error.getCause() : error;

        Component errorMessage;
        if (cause instanceof TimeoutException) {
            logger.error("REF:{} PlayerAPI registration timeout for player {} ({})",
                    refId, username, playerId);
            errorMessage = Component.text()
                    .content("PlayerAPI registration timeout\n")
                    .color(NamedTextColor.RED)
                    .append(Component.text("Please try again later\n", NamedTextColor.GRAY))
                    .append(Component.text("Reference: " + refId, NamedTextColor.DARK_GRAY)
                            .decorate(TextDecoration.ITALIC))
                    .build();
        } else {
            logger.error("REF:{} PlayerAPI registration failed for player {} ({}): {}",
                    refId, username, playerId, cause.getMessage(), cause);
            errorMessage = Component.text()
                    .content("Failed to register with PlayerAPI\n")
                    .color(NamedTextColor.RED)
                    .append(Component.text(cause.getMessage() + "\n", NamedTextColor.GRAY))
                    .append(Component.text("Reference: " + refId, NamedTextColor.DARK_GRAY)
                            .decorate(TextDecoration.ITALIC))
                    .build();
        }

        // Deny login
        event.setResult(LoginEvent.ComponentResult.denied(errorMessage));
    }

    /**
     * Handle player disconnect from the proxy.
     * This is async and does not block the disconnect process.
     */
    @Subscribe
    public void handleDisconnect(DisconnectEvent event) {
        final String playerName = event.getPlayer().getUsername();
        final UUID playerId = event.getPlayer().getUniqueId();

        playerApi.handleLogout(event.getPlayer())
                .thenAccept(success -> {
                    if (success) {
                        logger.info("Successfully logged out player {} ({})", playerName, playerId);
                    } else {
                        logger.warn("Logout for player {} ({}) returned false - player may not have been registered",
                                playerName, playerId);
                    }
                })
                .exceptionally(error -> {
                    String refId = UUID.randomUUID().toString().substring(0, 8);
                    logger.error("REF:{} Failed to logout player {} ({}): {}",
                            refId, playerName, playerId, error.getMessage(), error);
                    return null;
                });
    }

    /**
     * Handle player server changes within the proxy network.
     * Updates the player's current server in the PlayerAPI service.
     * <p>
     * Note: This only fires for server changes AFTER the initial connection.
     * The initial server connection happens automatically after LoginEvent succeeds.
     */
    @Subscribe
    public void handleServerChange(ServerPostConnectEvent event) {
        // Skip initial server connection (no previous server)
        if (event.getPreviousServer() == null) {
            // This is the initial connection - update the server in PlayerAPI
            var player = event.getPlayer();
            var currentServer = player.getCurrentServer()
                    .map(conn -> conn.getServerInfo().getName())
                    .orElse("unknown");

            logger.debug("Initial server connection for {} to {}, updating PlayerAPI...",
                    player.getUsername(), currentServer);

            updatePlayerServer(player.getUsername(), player.getUniqueId(), currentServer);
            return;
        }

        // This is a server switch
        var player = event.getPlayer();
        var currentServer = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("unknown");

        logger.debug("Player {} switched to server {}, updating PlayerAPI...",
                player.getUsername(), currentServer);

        updatePlayerServer(player.getUsername(), player.getUniqueId(), currentServer);
    }

    /**
     * Update the player's current server in PlayerAPI.
     * Logs warnings on failure but doesn't disconnect the player.
     */
    private void updatePlayerServer(String username, UUID playerId, String serverName) {
        var player = playerApi.getProxyServer().getPlayer(playerId);
        if (player.isEmpty()) {
            logger.warn("Cannot update server for {} - player not found locally", username);
            return;
        }

        playerApi.handleServerChange(player.get())
                .thenAccept(success -> {
                    if (success) {
                        logger.debug("Successfully updated server for player {} to {}", username, serverName);
                    } else {
                        logger.warn("Server update failed for player {} to {} - API returned false",
                                username, serverName);
                    }
                })
                .exceptionally(error -> {
                    String refId = UUID.randomUUID().toString().substring(0, 8);
                    logger.error("REF:{} Failed to update server for player {} to {}: {}",
                            refId, username, serverName, error.getMessage(), error);
                    return null;
                });
    }
}
