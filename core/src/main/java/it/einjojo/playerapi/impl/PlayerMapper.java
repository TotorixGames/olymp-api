package it.einjojo.playerapi.impl;

import it.einjojo.playerapi.AfkServiceApi;
import it.einjojo.playerapi.NetworkPlayer;
import it.einjojo.playerapi.OfflineNetworkPlayer;
import it.einjojo.protocol.player.GetOfflinePlayerResponse;
import it.einjojo.protocol.player.GetOnlinePlayerResponse;
import it.einjojo.protocol.player.OfflinePlayerDefinition;
import it.einjojo.protocol.player.OnlinePlayerDefinition;

import java.util.UUID;

public class PlayerMapper {

    public static OfflineNetworkPlayer toLocal(OfflinePlayerDefinition playerDefinition) {
        return new OfflineNetworkPlayerImpl(
                UUID.fromString(playerDefinition.getUniqueId()),
                playerDefinition.getUsername(),
                playerDefinition.getFirstLogin(),
                playerDefinition.getLastLogin(),
                playerDefinition.getOnlineTime(),
                playerDefinition.getOnline()
        );
    }

    public static NetworkPlayer toLocal(OnlinePlayerDefinition playerDefinition, AfkServiceApi afkServiceApi) {
        return new NetworkPlayerImpl(
                UUID.fromString(playerDefinition.getUniqueId()),
                playerDefinition.getUsername(),
                playerDefinition.getFirstLogin(),
                playerDefinition.getLastLogin(),
                playerDefinition.getOnlineTime(),
                true,
                playerDefinition.getConnectedServerName(),
                playerDefinition.getConnectedProxyName(),
                playerDefinition.getSessionId(),
                afkServiceApi
        );
    }

    public static NetworkPlayer readOnlineResponse(GetOnlinePlayerResponse response, AfkServiceApi afkServiceApi) {
        return toLocal(response.getPlayer(), afkServiceApi);
    }

    public static OfflineNetworkPlayer readOfflineResponse(GetOfflinePlayerResponse getOfflinePlayerResponse) {
        return toLocal(getOfflinePlayerResponse.getPlayer());
    }
}
