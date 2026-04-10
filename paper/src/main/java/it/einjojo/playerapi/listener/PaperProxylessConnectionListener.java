package it.einjojo.playerapi.listener;

import com.google.common.util.concurrent.ListenableFuture;
import it.einjojo.playerapi.impl.AbstractPlayerApi;
import it.einjojo.playerapi.util.InternalServerName;
import it.einjojo.protocol.player.LoginNotify;
import it.einjojo.protocol.player.LoginRequest;
import it.einjojo.protocol.player.LogoutRequest;
import it.einjojo.protocol.player.UpdateConnectionRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

/**
 * When the server is not running on a proxied system, the paper server should log in and log out the player.
 * This is useful for single testing servers, which require the plugin as a dependency.
 */
@NullMarked
public class PaperProxylessConnectionListener implements Listener {
    private static final Logger log = LoggerFactory.getLogger(PaperProxylessConnectionListener.class);
    private final MiniMessage miniMessage;
    private final AbstractPlayerApi playerApi;
    private final Executor executor;
    private final JavaPlugin plugin;

    /**
     * Constructor for PaperConnectionHandler.
     *
     * @param plugin    register events
     * @param playerApi api for login / logout operations
     * @param executor  for grpc future callbacks
     */
    public PaperProxylessConnectionListener(JavaPlugin plugin,
                                            AbstractPlayerApi playerApi,
                                            Executor executor) {
        this.playerApi = playerApi;
        this.plugin = plugin;
        this.executor = executor;
        this.miniMessage = MiniMessage.builder()
                .editTags(builder -> builder.tag("prefix", Tag.inserting(MiniMessage.miniMessage().deserialize("<#818cf8>ᴘʟᴀʏᴇʀᴀᴘɪ</#818cf8> <#eef2ff>"))))
                .build();

    }


    @EventHandler
    public void loginPlayer(PlayerJoinEvent event) {
        event.getPlayer().sendActionBar(miniMessage.deserialize("<prefix>Logging in..."));

        Player player = event.getPlayer();

        // Extract skin properties
        String skinTexture = "";
        String skinSignature = "";
        for (var property : player.getPlayerProfile().getProperties()) {
            if (property.getName().equals("textures")) {
                skinTexture = property.getValue();
                skinSignature = property.getSignature();
                break;
            }
        }

        // Extract connection information
        String ipAddress = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "127.0.0.1";
        String hostname = player.getAddress() != null
                ? player.getAddress().getHostString()
                : "";
        int protocolVersion = player.getProtocolVersion();
        String locale = player.locale().toString();
        long connectionTimestamp = System.currentTimeMillis();

        // Build login request with all available data
        var loginRequestBuilder = LoginRequest.newBuilder()
                .setProxyName("-")
                .setUsername(player.getName())
                .setUniqueId(player.getUniqueId().toString())
                .setIpAddress(ipAddress)
                .setProtocolVersion(protocolVersion)
                .setLocale(locale)
                .setConnectionTimestamp(connectionTimestamp);

        // Add optional skin data
        if (!skinTexture.isEmpty()) {
            loginRequestBuilder.setSkinTexture(skinTexture);
        }
        if (skinSignature != null && !skinSignature.isEmpty()) {
            loginRequestBuilder.setSkinSignature(skinSignature);
        }
        if (!hostname.isEmpty()) {
            loginRequestBuilder.setHostname(hostname);
        }

        ListenableFuture<LoginNotify> loginFuture = playerApi.playerServiceStub.login(
                loginRequestBuilder.build());

        loginFuture.addListener(() -> {
            try {
                var result = loginFuture.resultNow();
                event.getPlayer().sendActionBar(miniMessage.deserialize("<prefix>Logged in as " + result.getPlayer().getUsername()));
                updateServer(event.getPlayer());
            } catch (Exception ex) {
                log.error("Player login failed for player {}", event.getPlayer().getName(), ex);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    event.getPlayer().sendMessage(miniMessage.deserialize("<prefix><red>Login failed: " + ex.getMessage()));
                });
            }


        }, executor);
    }


    private void updateServer(Player player) {
        log.info("Successfully logged in player {}", player.getName());
        String serverName = InternalServerName.get();
        player.sendActionBar(miniMessage.deserialize("<prefix>Angemeldet! Setze Server auf " + serverName + "..."));
        var future = playerApi.playerServiceStub.updateConnection(UpdateConnectionRequest.newBuilder()
                .setUniqueId(player.getUniqueId().toString())
                .setConnectedServerName(serverName)
                .build());
        future.addListener(() -> {
            try {
                var result = future.resultNow();
                player.sendActionBar(miniMessage.deserialize("<prefix>Du bist jetzt online auf " + serverName));
            } catch (Exception e) {
                log.error("Updating server failed for player {}", player.getName(), e);
                player.sendActionBar(miniMessage.deserialize("<prefix><red>Server switch failed: " + e.getMessage()));
            }


        }, executor);
    }

    @EventHandler
    private void logoutPlayer(PlayerQuitEvent event) {
        playerApi.playerServiceStub.logout(LogoutRequest.newBuilder()
                .setUniqueId(event.getPlayer().getUniqueId().toString())
                .build()).addListener(() -> log.info("Successfully logged out player {}", event.getPlayer().getName()), executor);
    }

}
