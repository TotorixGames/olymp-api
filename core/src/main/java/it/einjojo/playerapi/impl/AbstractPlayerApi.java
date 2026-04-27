package it.einjojo.playerapi.impl;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import it.einjojo.playerapi.*;
import it.einjojo.protocol.player.*;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract implementation of the PlayerApi interface that provides common functionality for accessing player data.
 * This class uses gRPC to communicate with a player service and provides methods to retrieve player information
 */
public abstract class AbstractPlayerApi implements PlayerApi {

    public PlayerServiceGrpc.PlayerServiceFutureStub playerServiceStub;
    private final AfkServiceApi afkServiceApi;
    protected final Executor executor;
    private static final Empty EMPTY = Empty.getDefaultInstance();

    /**
     * Constructor for AbstractPlayerApi.
     *
     * @param channel  the gRPC channel to communicate with the player service
     * @param executor the executor to run the callbacks on
     */
    public AbstractPlayerApi(ManagedChannel channel, Executor executor) {
        this.playerServiceStub = PlayerServiceGrpc.newFutureStub(channel);
        this.afkServiceApi = new AfkServiceApiImpl(channel, executor);
        this.executor = executor;
    }

    /**
     * Returns the {@link AfkServiceApi} used to resolve AFK status for online players.
     *
     * @return the AFK service API; never {@code null}
     */
    protected AfkServiceApi getAfkServiceApi() {
        return afkServiceApi;
    }

    private List<NetworkPlayer> extractOnlinePlayers(GetOnlinePlayersResponse response) {
        if (response.getPlayersList().isEmpty()) return List.of();
        return response.getPlayersList().stream()
                .map(def -> PlayerMapper.toLocal(def, afkServiceApi))
                .toList();
    }

    @Override
    public CompletableFuture<List<NetworkPlayer>> getOnlinePlayers() {
        ListenableFuture<GetOnlinePlayersResponse> future = playerServiceStub.getOnlinePlayers(EMPTY);
        return createCallback(future, this::extractOnlinePlayers);
    }

    @Override
    public CompletableFuture<List<String>> getOnlinePlayerNames() {
        ListenableFuture<GetOnlinePlayerNamesResponse> future = playerServiceStub.getOnlinePlayerNames(EMPTY);
        return createCallback(future, (GetOnlinePlayerNamesResponse::getNamesList));
    }

    @Override
    public CompletableFuture<OfflineNetworkPlayer> getOfflinePlayer(String playerName) {
        if (playerName == null) return CompletableFuture.completedFuture(null);
        var future = playerServiceStub.getOfflinePlayerByName(PlayerNameRequest.newBuilder().setName(playerName).build());
        return createCallback(future, PlayerMapper::readOfflineResponse);
    }

    @Override
    public CompletableFuture<OfflineNetworkPlayer> getOfflinePlayer(UUID playerUUID) {
        if (playerUUID == null) return CompletableFuture.completedFuture(null);
        var future = playerServiceStub.getOfflinePlayerByUniqueId(PlayerIdRequest.newBuilder().setUniqueId(playerUUID.toString()).build());
        return createCallback(future, PlayerMapper::readOfflineResponse);
    }

    @Override
    public CompletableFuture<Boolean> isPlayerOnline(String playerName) {
        if (playerName == null) return CompletableFuture.completedFuture(Boolean.FALSE);
        if (getLocalOnlinePlayerAccessor().isOnline(playerName))
            return CompletableFuture.completedFuture(Boolean.TRUE);
        return getOfflinePlayer(playerName).thenApply(OfflineNetworkPlayer::isOnline);
    }

    @Override
    public CompletableFuture<Boolean> isPlayerOnline(UUID playerUUID) {
        if (playerUUID == null) return CompletableFuture.completedFuture(Boolean.FALSE);
        if (getLocalOnlinePlayerAccessor().isOnline(playerUUID))
            return CompletableFuture.completedFuture(Boolean.TRUE);
        return getOfflinePlayer(playerUUID).thenApply(OfflineNetworkPlayer::isOnline);
    }

    @Override
    public CompletableFuture<NetworkPlayer> getOnlinePlayer(String playerName) {
        if (playerName == null) return CompletableFuture.completedFuture(null);
        ListenableFuture<GetOnlinePlayerResponse> future = playerServiceStub.getOnlinePlayerByName(PlayerNameRequest.newBuilder().setName(playerName).build());
        return createCallback(future, response -> PlayerMapper.readOnlineResponse(response, afkServiceApi));
    }

    @Override
    public CompletableFuture<NetworkPlayer> getOnlinePlayer(UUID playerUUID) {
        if (playerUUID == null) return CompletableFuture.completedFuture(null);
        ListenableFuture<GetOnlinePlayerResponse> future = playerServiceStub.getOnlinePlayerByUniqueId(PlayerIdRequest.newBuilder().setUniqueId(playerUUID.toString()).build());
        return createCallback(future, response -> PlayerMapper.readOnlineResponse(response, afkServiceApi));
    }

    @Override
    public CompletableFuture<UUID> getUniqueId(String playerName) {
        if (playerName == null) return CompletableFuture.completedFuture(null);
        ListenableFuture<UniqueIdLookupResponse> future = playerServiceStub.getUniqueIdByName(PlayerNameRequest.newBuilder().setName(playerName).build());
        return createCallback(future, UniqueIdLookupResponse::getUniqueId).thenApply(UUID::fromString);
    }

    protected <Type, ResultType> CompletableFuture<Type> createCallback(ListenableFuture<ResultType> listenableFuture, Function<ResultType, Type> mapper) {
        CompletableFuture<Type> completableFuture = new CompletableFuture<>();
        listenableFuture.addListener(() -> {
            try {
                if (listenableFuture.state() == Future.State.SUCCESS) {
                    ResultType result = listenableFuture.get();
                    completableFuture.complete(mapper.apply(result));
                } else {
                    Throwable t = listenableFuture.exceptionNow();
                    if (t instanceof StatusRuntimeException statusRuntimeException) {
                        Status status = statusRuntimeException.getStatus();
                        if (status.getCode() == Status.Code.NOT_FOUND) {
                            completableFuture.complete(null);
                            return;
                        }
                    }
                    completableFuture.completeExceptionally(t);
                }
            } catch (Exception e) {
                completableFuture.completeExceptionally(e);
            }
        }, executor);
        return completableFuture;
    }

    @Override
    public Closeable subscribeLogin(@NotNull Consumer<NetworkPlayer> playerConsumer) {
        return getRedisPubSubHandler().subscribeLogin(((notify) -> {
            playerConsumer.accept(PlayerMapper.toLocal(notify.getPlayer(), afkServiceApi));
        }));
    }

    @Override
    public Closeable subscribeLogout(@NotNull Consumer<OfflineNetworkPlayer> offlinePlayerConsumer) {
        return getRedisPubSubHandler().subscribeLogout(((notify) -> {
            offlinePlayerConsumer.accept(PlayerMapper.toLocal(notify.getPlayer()));
        }));
    }

    protected abstract RedisPubSubHandler getRedisPubSubHandler();

    public abstract LocalOnlinePlayerAccessor getLocalOnlinePlayerAccessor();

    /**
     * Closes internal resources.
     */
    public void shutdown() {
        getRedisPubSubHandler().close();
    }
}
