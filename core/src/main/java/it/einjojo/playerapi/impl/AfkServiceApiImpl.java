package it.einjojo.playerapi.impl;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import it.einjojo.playerapi.AfkServiceApi;
import net.totorix.protocol.player.AFKServiceGrpc;
import net.totorix.protocol.player.GetAFKStatusRequest;
import net.totorix.protocol.player.GetAFKStatusResponse;
import net.totorix.protocol.player.SetAFKRequest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * gRPC-backed implementation of {@link AfkServiceApi}.
 *
 * <p>Uses a non-blocking {@link AFKServiceGrpc.AFKServiceFutureStub} for all remote calls.
 * The stub is created once at construction time and reused for the lifetime of this instance.
 * {@code NOT_FOUND} responses are treated as {@code null} completions rather than exceptional ones,
 * consistent with the rest of the API.
 */
public class AfkServiceApiImpl implements AfkServiceApi {

    private final AFKServiceGrpc.AFKServiceFutureStub stub;
    private final Executor executor;

    /**
     * @param channel  the shared gRPC {@link ManagedChannel}; must not be {@code null}
     * @param executor the executor on which response callbacks are dispatched
     */
    public AfkServiceApiImpl(ManagedChannel channel, Executor executor) {
        this.stub = AFKServiceGrpc.newFutureStub(channel);
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Boolean> isAfk(UUID playerUUID) {
        GetAFKStatusRequest request = GetAFKStatusRequest.newBuilder()
                .setUniqueId(playerUUID.toString())
                .build();
        return createCallback(stub.getAFKStatus(request), GetAFKStatusResponse::getAfk);
    }

    @Override
    public CompletableFuture<Void> setAfk(UUID playerUUID, boolean afk) {
        SetAFKRequest request = SetAFKRequest.newBuilder()
                .setUniqueId(playerUUID.toString())
                .setAfk(afk)
                .build();
        return createCallback(stub.setAFK(request), ignored -> null);
    }

    private <T, R> CompletableFuture<T> createCallback(ListenableFuture<R> future, Function<R, T> mapper) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        future.addListener(() -> {
            try {
                if (future.state() == Future.State.SUCCESS) {
                    completableFuture.complete(mapper.apply(future.get()));
                } else {
                    Throwable t = future.exceptionNow();
                    if (t instanceof StatusRuntimeException sre
                            && sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
                        completableFuture.complete(null);
                        return;
                    }
                    completableFuture.completeExceptionally(t);
                }
            } catch (Exception e) {
                completableFuture.completeExceptionally(e);
            }
        }, executor);
        return completableFuture;
    }
}
