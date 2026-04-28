package it.einjojo.playerapi;


import io.grpc.ManagedChannel;
import io.lettuce.core.RedisClient;
import it.einjojo.playerapi.config.PluginConfig;
import it.einjojo.playerapi.config.RedisConnectionConfiguration;
import it.einjojo.playerapi.config.SharedConnectionConfiguration;
import it.einjojo.playerapi.impl.AbstractPlayerApi;
import it.einjojo.playerapi.listener.PaperConnectionVerifyListener;
import it.einjojo.playerapi.listener.PaperProxylessConnectionListener;
import it.einjojo.protocol.player.PlayerServiceGrpc;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * Has config and supports the shared connection specification.
 * If running the server in online mode, the plugin will handle login and logout.
 */
public class PaperPlayerApiProviderPlugin extends JavaPlugin {
    private final Logger log = getSLF4JLogger();
    public static PaperPlayerApiProviderPlugin INSTANCE;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ManagedChannel channel;


    /**
     * Performs redis connection tests and provides the paper api implementation.
     */
    @Override
    public void onEnable() {
        INSTANCE = this;
        PluginConfig config = PluginConfig.load(getDataPath());
        var sharedConfig = SharedConnectionConfiguration.load();
        RedisConnectionConfiguration redisConfig = sharedConfig.map(SharedConnectionConfiguration::redis).orElseGet(config::redis);
        try (var client = RedisClient.create(redisConfig.createUri("playerapi")); var con = client.connect()) {
            log.info("Pinging redis server {}... ", con.sync().ping());
        } catch (Exception ex) {
            log.error("{} | SharedConfig available: {} \n  ==> Your {} \n", ex.getMessage(), sharedConfig.isPresent(), redisConfig);
            getSLF4JLogger().info("Disabling PlayerApi plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        channel = config.createChannel(executor);
        var state = channel.getState(true);
        log.info("gRPC channel to PlayerApi server is in state: {}", state);
        channel.notifyWhenStateChanged(state, () -> {
            var newState = channel.getState(true);
            log.info("gRPC channel to PlayerApi server changed state: {}", newState);
        });
        PaperPlayerApi playerApi = new PaperPlayerApi(channel, executor, redisConfig);
        getServer().getServicesManager().register(AfkServiceApi.class, playerApi.getAfkServiceApi(), this, ServicePriority.Normal);
        PlayerApiProvider.register(playerApi);
        Bukkit.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getSLF4JLogger().info("PlayerApi Paper plugin has been initialized.");
        if (getServer().getOnlineMode()) {
            getSLF4JLogger().info("Detected online mode. This Server will handle authentication for players.");
            getServer().getPluginManager().registerEvents(new PaperProxylessConnectionListener(this, playerApi, executor), this);
        } else {
            getSLF4JLogger().info("Detected offline mode. This Server will verify players' sessions");
            getServer().getPluginManager().registerEvents(new PaperConnectionVerifyListener(log, PlayerServiceGrpc.newBlockingV2Stub(channel), this), this);
        }
    }


    @Override
    public void onDisable() {
        log.info("Shutting down PlayerApi...");

        // 1. Shutdown PlayerApi (closes Redis connections)
        try {
            ((AbstractPlayerApi) PlayerApiProvider.getInstance()).shutdown();
        } catch (Exception e) {
            log.error("Error during PlayerApi shutdown", e);
        }

        // 2. Shutdown executor service
        shutdownExecutor();

        // 3. Shutdown gRPC channel
        shutdownChannel();

        log.info("PlayerApi shutdown complete.");
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate gracefully, forcing shutdown...");
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted during executor shutdown", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownChannel() {
        if (channel == null) {
            return;
        }

        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("gRPC channel did not terminate gracefully, forcing shutdown...");
                channel.shutdownNow();
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("gRPC channel did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted during gRPC shutdown", e);
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
