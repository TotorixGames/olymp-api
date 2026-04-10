package it.einjojo.playerapi;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.grpc.ManagedChannel;
import it.einjojo.playerapi.config.PluginConfig;
import it.einjojo.playerapi.config.RedisConnectionConfiguration;
import it.einjojo.playerapi.config.SharedConnectionConfiguration;
import it.einjojo.playerapi.impl.AbstractPlayerApi;
import it.einjojo.playerapi.listener.ConnectionListener;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "playerapi-velocity",
        name = "PlayerApi Velocity",
        version = "1.0.0",
        description = "A plugin that provides PlayerApi functionality for Velocity.",
        authors = {"EinjoJo"},
        url = "https://einjojo.it/work/springx"

)
public class VelocityPlayerApiProviderPlugin {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ManagedChannel channel;

    @Inject
    public VelocityPlayerApiProviderPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.load(dataDirectory);
        channel = config.createChannel(executor);
        RedisConnectionConfiguration redis = SharedConnectionConfiguration.load().map(SharedConnectionConfiguration::redis).orElseGet(config::redis);
        VelocityPlayerApi playerApi = new VelocityPlayerApi(channel, executor, server, redis);
        PlayerApiProvider.register(playerApi);
        server.getEventManager().register(this, new ConnectionListener(playerApi, logger));
        logger.info("PlayerApi Velocity plugin has been initialized.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down PlayerApi...");

        // 1. Shutdown PlayerApi (closes Redis connections)
        try {
            ((AbstractPlayerApi) PlayerApiProvider.getInstance()).shutdown();
        } catch (Exception e) {
            logger.error("Error during PlayerApi shutdown", e);
        }

        // 2. Shutdown executor service
        shutdownExecutor();

        // 3. Shutdown gRPC channel
        shutdownChannel();

        logger.info("PlayerApi shutdown complete.");
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate gracefully, forcing shutdown...");
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error("Executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during executor shutdown", e);
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
                logger.warn("gRPC channel did not terminate gracefully, forcing shutdown...");
                channel.shutdownNow();
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error("gRPC channel did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during gRPC shutdown", e);
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


}
