/*
 * DragonProxy
 * Copyright (C) 2016-2020 Dragonet Foundation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You can view the LICENSE file for more details.
 *
 * https://github.com/DragonetMC/DragonProxy
 */
package org.dragonet.proxy.network.session;

import com.github.steveice10.mc.auth.data.GameProfile;
import com.github.steveice10.mc.auth.exception.property.PropertyException;
import com.github.steveice10.mc.auth.exception.request.RequestException;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.SubProtocol;
import com.github.steveice10.mc.protocol.data.game.ClientRequest;
import com.github.steveice10.mc.protocol.data.game.entity.player.GameMode;
import com.github.steveice10.mc.protocol.data.game.statistic.Statistic;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientRequestPacket;
import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.event.session.ConnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.packet.Packet;
import com.github.steveice10.packetlib.tcp.TcpSessionFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nukkitx.math.vector.Vector2f;
import com.nukkitx.math.vector.Vector3f;
import com.nukkitx.math.vector.Vector3i;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.protocol.PlayerSession;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.data.*;
import com.nukkitx.protocol.bedrock.packet.*;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.dragonet.proxy.DragonProxy;
import org.dragonet.proxy.form.CustomForm;
import org.dragonet.proxy.form.ModalForm;
import org.dragonet.proxy.form.components.ButtonComponent;
import org.dragonet.proxy.form.components.InputComponent;
import org.dragonet.proxy.form.components.LabelComponent;
import org.dragonet.proxy.network.session.cache.*;
import org.dragonet.proxy.network.session.cache.object.CachedEntity;
import org.dragonet.proxy.network.session.cache.object.CachedPlayer;
import org.dragonet.proxy.network.session.data.AuthData;
import org.dragonet.proxy.network.session.data.AuthState;
import org.dragonet.proxy.network.session.data.ClientData;
import org.dragonet.proxy.network.translator.PacketTranslatorRegistry;
import org.dragonet.proxy.network.translator.misc.BlockTranslator;
import org.dragonet.proxy.remote.RemoteAuthType;
import org.dragonet.proxy.remote.RemoteServer;
import org.dragonet.proxy.util.PaletteManager;
import org.dragonet.proxy.util.SkinUtils;
import org.dragonet.proxy.util.TextFormat;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a bedrock player session.
 */
@Data
@Log4j2
public class ProxySession implements PlayerSession {
    private final DragonProxy proxy;
    private RemoteServer remoteServer;
    private MinecraftProtocol protocol;
    private BedrockServerSession bedrockSession;
    private Client downstream;
    private volatile boolean closed;

    private String username;

    private Map<String, Object> dataCache = new HashMap<>();
    private Map<Integer, CompletableFuture> formCache = new HashMap<>();
    private Map<String, CompletableFuture> futureMap = new HashMap<>(); // TODO

    private AtomicInteger formIdCounter = new AtomicInteger();

    private EntityCache entityCache = new EntityCache();
    private WindowCache windowCache = new WindowCache();
    private ChunkCache chunkCache = new ChunkCache();
    private WorldCache worldCache = new WorldCache();
    private PlayerListCache playerListCache = new PlayerListCache();

    private CachedPlayer cachedEntity;

    private AuthData authData;
    private ClientData clientData;

    private int renderDistance = 8;

    private Vector3i lastClickedPosition = null;
    private CachedEntity lastClickedEntity = null;

    public ProxySession(DragonProxy proxy, BedrockServerSession bedrockSession) {
        this.proxy = proxy;
        this.bedrockSession = bedrockSession;
        this.bedrockSession.setLogging(true);

        dataCache.put("auth_state", AuthState.NONE);

        // Disconnect the player from the remote server when they disconnect
        bedrockSession.addDisconnectHandler((reason) -> {
            if (downstream != null && downstream.getSession() != null) {
                if (cachedEntity != null) {
                    cachedEntity.destroy(this);
                }
                downstream.getSession().disconnect(reason.name());
            }
        });
    }

    /**
     * Connect to a remote server.
     *
     * @param server the server to connect to
     */
    public void connect(RemoteServer server) {

        // Enable coordinates now
        GameRulesChangedPacket gameRulesChangedPacket = new GameRulesChangedPacket();
        gameRulesChangedPacket.getGameRules().add(new GameRuleData<>("showcoordinates", true));
        bedrockSession.sendPacket(gameRulesChangedPacket);

        if (protocol == null) {
            protocol = new MinecraftProtocol(authData.getDisplayName());
        }
        downstream = new Client(server.getAddress(), server.getPort(), protocol, new TcpSessionFactory());
        downstream.getSession().addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                log.info(String.format("[%s] %s has connected to %s:%d", bedrockSession.getAddress().toString(), authData.getDisplayName(), server.getAddress(), server.getPort()));
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                if(event.getCause() != null) {
                    bedrockSession.disconnect(String.format("We were unable to connect you %s:%d\nAre you sure that's the correct IP?", server.getAddress(), server.getPort()));
                } else {
                    log.info(String.format("[%s] %s has disconnected from %s:%d", bedrockSession.getAddress().toString(), authData.getDisplayName(), server.getAddress(), server.getPort()));
                }
            }

            @Override
            public void packetReceived(PacketReceivedEvent event) {
                try {
                    PacketTranslatorRegistry.JAVA_TO_BEDROCK.translate(ProxySession.this, event.getPacket());
                } catch (Exception e) {
                    log.throwing(e);
                }
            }
        });

        downstream.getSession().connect();
        remoteServer = server;
    }

    /**
     * Authenticate the user with Mojang account services.
     * This method is only called if `auth-mode` is set to `online`.
     *
     * @param email    the mojang account email
     * @param password the mojang account password
     */
    public void authenticate(String server, int port, String email, String password) {
        proxy.getGeneralThreadPool().execute(() -> {
            try {
                protocol = new MinecraftProtocol(email, password);
            } catch (RequestException e) {
                //log.warn("Failed to authenticate player: " + e.getMessage());
                sendMessage(TextFormat.RED + e.getMessage());
                connectExternal();
                return;
            }

            // Set our own remote uuid
            cachedEntity.setJavaUuid(protocol.getProfile().getId());

            // Fetch our skin
            if(proxy.getConfiguration().getPlayerConfig().isFetchSkin()) {
                fetchOurSkin();
            }

            sendMessage(TextFormat.GREEN + "Login successful! Joining server...");

            if (!username.equals(protocol.getProfile().getName())) {
                username = protocol.getProfile().getName();
                sendMessage(TextFormat.AQUA + "You username was changed to " + TextFormat.DARK_AQUA + username + TextFormat.AQUA + " like your Mojang account username");
            }

            // Empty line to seperate DragonProxy messages from server messages
            sendMessage(" ");

            // Start connecting to remote server
            RemoteServer remoteServer = new RemoteServer("local", server, port);
            connect(remoteServer);

            dataCache.put("auth_state", AuthState.AUTHENTICATED);
        });
    }

    /**
     * Connect to CapeCraft
     */
    private void connectCapeCraft() {
        // Empty line to seperate DragonProxy messages from server messages
        sendMessage(" ");

        // Start connecting to remote server
        RemoteServer remoteServer = new RemoteServer("local", "127.0.0.1", 25577);
        connect(remoteServer);
    }

    /**
     * Connect to an external server
     */
    private void connectExternal() {
        dataCache.put("auth_state", AuthState.AUTHENTICATING);

        CustomForm form = new CustomForm(TextFormat.BLUE + "Connect to external server")
            .addComponent(new LabelComponent(TextFormat.GREEN + "Please enter the server ip and your Mojang credentials to connect"))
            .addComponent(new InputComponent(TextFormat.AQUA + "IP", "play.capecraft.net"))
            .addComponent(new InputComponent(TextFormat.AQUA + "Email", "steve@example.com"))
            .addComponent(new InputComponent(TextFormat.AQUA + "Password", "123456"));

        form.send(this).whenComplete((data, throwable) -> {
            if (data.equalsIgnoreCase("null")) {
                connectExternal();
                return;
            }

            JsonArray object = new JsonParser().parse(data).getAsJsonArray();

            String serverIp = new Server(object.get(1).getAsString()).getIp();
            int serverPort = new Server(object.get(1).getAsString()).getPort();
            String email = object.get(2).getAsString();
            String password = object.get(3).getAsString();

            if (serverIp.isEmpty() || email.isEmpty() || password.isEmpty()) {
                sendMessage(TextFormat.RED + "Please fill in all the required fields.");
                connectExternal();
                return;
            }

            authenticate(serverIp, serverPort, email, password);
        });
    }

    /**
     * Fetch our own skin.
     */
    private void fetchOurSkin() {
        GameProfile profile = protocol.getProfile();

        proxy.getGeneralThreadPool().execute(() -> {
            ImageData skinData = SkinUtils.fetchSkin(this, profile);
            if (skinData == null) return;

            ImageData capeData = SkinUtils.fetchCape(this, profile);
            if(capeData == null) capeData = ImageData.EMPTY;

            GameProfile.TextureModel model = null;
            try {
                model = profile.getTexture(GameProfile.TextureType.SKIN).getModel();
            } catch (PropertyException e) {
                //log.warn("Failed to get skin model for player " + profile.getName(), e);
            }
            setPlayerSkin2(authData.getIdentity(), skinData, model, capeData);
        });
    }

    /**
     * Fetch statistics from the remote server.
     *
     * @return a future that completes if the server responds to the stats request
     */
    public CompletableFuture<Map<Statistic, Integer>> fetchStatistics() {
        CompletableFuture<Map<Statistic, Integer>> future = new CompletableFuture<>();

        // TODO: this can overwrite the previous future even if its not yet been completed
        // We may want to add better handling for this
        futureMap.put("stats", future);

        ClientRequestPacket packet = new ClientRequestPacket(ClientRequest.STATS);
        sendRemotePacket(packet);
        return future;
    }

    /**
     * This method handles the initial bedrock client connection.
     */
    public void handleJoin() {
        sendFakeStartGame(false);
    }

    /**
     * Displays a form to choose options
     */
    public void sendConnectionPrompt() {
        ModalForm form = new ModalForm(TextFormat.BLUE + "This is a test", "Choose Server")
            .addComponent(new ButtonComponent("§r§l» §2§lCapeCraft§r §r§l«\n§r§l» §9§lSURVIVAL & CREATIVE §r§l«", "https://capecraft.net/assets/img/logo/bedrock-logo.png"))
            .addComponent(new ButtonComponent("Other Server"));

        form.send(this).whenComplete((data, throwable) -> {
            if(data == null) {
                sendConnectionPrompt();
                return;
            }

            switch(Integer.parseInt(data)) {
                case 0:
                    connectCapeCraft();
                    return;
                case 1:
                    connectExternal();
                    return;
                default:
                    sendConnectionPrompt();
            }
        });
    }

    /**
     * Adds the player to the player list
     * @param entityId
     */
    public void spawn(long entityId) {
        PlayerListPacket.Entry entry = new PlayerListPacket.Entry(authData.getIdentity());
        entry.setEntityId(entityId);
        entry.setName(authData.getDisplayName());

        SerializedSkin skin = SerializedSkin.of(
            clientData.getSkinId(),
            new String(Base64.getDecoder().decode(clientData.getSkinGeometryName())),
            ImageData.of(clientData.getSkinImageWidth(), clientData.getSkinImageHeight(), clientData.getSkinData()),
            Collections.emptyList(),
            ImageData.of(clientData.getCapeImageWidth(), clientData.getCapeImageHeight(), clientData.getCapeData()),
            new String(clientData.getSkinGeometry(), StandardCharsets.UTF_8),
            "",
            clientData.isPremiumSkin(),
            clientData.isPersonaSkin(),
            clientData.isCapeOnClassicSkin(),
            clientData.getCapeId(),
            clientData.getSkinId());

        entry.setXuid(authData.getXuid() == null ? "" : authData.getXuid());
        entry.setPlatformChatId("");
        entry.setSkin(skin);
        entry.setHost(false);
        entry.setBuildPlatform(0);

        PlayerListPacket playerListPacket = new PlayerListPacket();
        playerListPacket.setAction(PlayerListPacket.Action.ADD);
        playerListPacket.getEntries().add(entry);

        bedrockSession.sendPacket(playerListPacket);

        cachedEntity.sendMetadata(this);
        cachedEntity.sendAttributes(this);

        // Set the render distance
        ChunkRadiusUpdatedPacket chunkRadiusUpdatedPacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatedPacket.setRadius(renderDistance);
        sendPacket(chunkRadiusUpdatedPacket);

        if(proxy.getConfiguration().getRemoteAuthType() == RemoteAuthType.CREDENTIALS) {
            sendConnectionPrompt();
            return;
        }

        // Start connecting to remote server.
        // There is a slight delay before connection because otherwise the player will join
        // the server too quickly and chunks will not show.
        if(proxy.getConfiguration().getRemoteAuthType() == RemoteAuthType.OFFLINE) {
            sendMessage(TextFormat.DARK_AQUA + "Waiting 2 seconds before connecting...");

            proxy.getGeneralThreadPool().schedule(() -> {
                sendMessage(" ");

                RemoteServer remoteServer = new RemoteServer("local", proxy.getConfiguration().getRemoteAddress(), proxy.getConfiguration().getRemotePort());
                connect(remoteServer);
            }, 2, TimeUnit.SECONDS);
        }
    }

    public void sendFakeStartGame(boolean disconnect) {
        long entityId = 1;
        if(!disconnect) {
            entityId = entityCache.getNextClientEntityId().getAndIncrement();
        }

        StartGamePacket startGamePacket = new StartGamePacket();
        startGamePacket.setUniqueEntityId(entityId);
        startGamePacket.setRuntimeEntityId(entityId);
        startGamePacket.setPlayerGamemode(0);
        startGamePacket.setPlayerPosition(Vector3f.from(0, 50, 0));
        startGamePacket.setRotation(Vector2f.ZERO);

        startGamePacket.setSeed(0);
        startGamePacket.setDimensionId(0);
        startGamePacket.setGeneratorId(1);
        startGamePacket.setLevelGamemode(0);
        startGamePacket.setDifficulty(0);
        startGamePacket.setDefaultSpawn(Vector3i.ZERO);
        startGamePacket.setAchievementsDisabled(true);
        startGamePacket.setTime(0);
        startGamePacket.setEduEditionOffers(0);
        startGamePacket.setEduFeaturesEnabled(false);
        startGamePacket.setRainLevel(0);
        startGamePacket.setLightningLevel(0);
        startGamePacket.setMultiplayerGame(true);
        startGamePacket.setBroadcastingToLan(true);
        startGamePacket.getGamerules().add((new GameRuleData<>("showcoordinates", true)));
        startGamePacket.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGamePacket.setCommandsEnabled(true);
        startGamePacket.setTexturePacksRequired(false);
        startGamePacket.setBonusChestEnabled(false);
        startGamePacket.setStartingWithMap(false);
        startGamePacket.setTrustingPlayers(true);
        startGamePacket.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        startGamePacket.setServerChunkTickRange(4);
        startGamePacket.setBehaviorPackLocked(false);
        startGamePacket.setResourcePackLocked(false);
        startGamePacket.setFromLockedWorldTemplate(false);
        startGamePacket.setUsingMsaGamertagsOnly(false);
        startGamePacket.setFromWorldTemplate(false);
        startGamePacket.setWorldTemplateOptionLocked(false);

        startGamePacket.setLevelId(UUID.randomUUID().toString());
        startGamePacket.setWorldName("DragonProxy " + proxy.getVersion());
        startGamePacket.setPremiumWorldTemplateId("00000000-0000-0000-0000-000000000000");
        startGamePacket.setCurrentTick(0);
        startGamePacket.setEnchantmentSeed(0);
        startGamePacket.setMultiplayerCorrelationId("");

        startGamePacket.setMovementServerAuthoritative(false);
        startGamePacket.setVanillaVersion(DragonProxy.BEDROCK_CODEC.getMinecraftVersion());

        startGamePacket.setBlockPalette(BlockTranslator.BLOCK_PALETTE);
        startGamePacket.setItemEntries(PaletteManager.ITEM_PALETTE);
        bedrockSession.sendPacketImmediately(startGamePacket);

        BiomeDefinitionListPacket biomeDefinitionListPacket = new BiomeDefinitionListPacket();
        biomeDefinitionListPacket.setTag(PaletteManager.BIOME_ENTRIES);
        sendPacket(biomeDefinitionListPacket);

        AvailableEntityIdentifiersPacket entityPacket = new AvailableEntityIdentifiersPacket();
        entityPacket.setTag(PaletteManager.ENTITY_IDENTIFIERS);
        sendPacket(entityPacket);

        // Spawn
        PlayStatusPacket playStatusPacket = new PlayStatusPacket();
        playStatusPacket.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        sendPacket(playStatusPacket);

        if(!disconnect) {
            cachedEntity = entityCache.newPlayer(1, entityId, new GameProfile(getAuthData().getIdentity(), getAuthData().getDisplayName()));
        }
    }

    /**
     * Set a skin for the specified player.
     *
     * @param playerId the target player uuid
     * @param entityId
     * @param skinData the skin data
     */
    public void setPlayerSkin(UUID playerId, long entityId, ImageData skinData, GameProfile.TextureModel model, ImageData capeData) {
        GameProfile profile = playerListCache.getPlayerInfo().get(playerId).getEntry().getProfile();

        // Remove the player from the player list
        PlayerListPacket removePacket = new PlayerListPacket();
        removePacket.setAction(PlayerListPacket.Action.REMOVE);
        removePacket.getEntries().add(new PlayerListPacket.Entry(playerId));
        sendPacket(removePacket);

        // Add them back to the player list with a new skin
        PlayerListPacket addPacket = new PlayerListPacket();
        addPacket.setAction(PlayerListPacket.Action.ADD);

        PlayerListPacket.Entry entry = new PlayerListPacket.Entry(playerId);
        entry.setEntityId(entityId);
        entry.setName(profile.getName());
        entry.setSkin(SkinUtils.createSkinEntry(skinData, model, capeData));
        entry.setXuid("");
        entry.setPlatformChatId("");

        addPacket.getEntries().add(entry);
        sendPacket(addPacket); // TODO

        // TODO: ideally we would use PlayerSkinPacket, but that crashes...
        // See below
    }

    /**
     * Currently used for setting our own skin, however hopefully it can be used to
     * set other players' skins in the future instead of using the player list hack
     *
     * @param playerId the target player uuid
     * @param skinData the skin data
     */
    public void setPlayerSkin2(UUID playerId, ImageData skinData, GameProfile.TextureModel model, ImageData capeData) {
        PlayerSkinPacket playerSkinPacket = new PlayerSkinPacket();
        playerSkinPacket.setUuid(playerId);

        playerSkinPacket.setSkin(SkinUtils.createSkinEntry(skinData, model, capeData));
        sendPacket(playerSkinPacket);
    }

    public void sendMessage(String text) {
        sendMessage(text, TextPacket.Type.RAW);
    }

    /**
     * Send a message to the player.
     *
     * @param text
     * @param type
     */
    public void sendMessage(String text, TextPacket.Type type) {
        TextPacket packet = new TextPacket();
        packet.setType(type);
        packet.setNeedsTranslation(false);
        packet.setXuid("");
        packet.setSourceName("");
        packet.setPlatformChatId("");
        packet.setMessage(text);

        bedrockSession.sendPacket(packet);
    }

    public void sendCreativeInventory() {
        // TODO: make this mess into some Java 8 lambda magic
        ItemData[] creativeItems = new ItemData[PaletteManager.CREATIVE_ITEMS.size()];
        for (int i = 0; i < PaletteManager.CREATIVE_ITEMS.size(); i++) {
            PaletteManager.RuntimeCreativeItemEntry entry = PaletteManager.CREATIVE_ITEMS.get(i);

            creativeItems[i] = ItemData.of(entry.getId(), (short) entry.getDamage(), 1);
        }

        InventoryContentPacket inventoryContentPacket = new InventoryContentPacket();
        inventoryContentPacket.setContainerId(ContainerId.CREATIVE);
        inventoryContentPacket.setContents(creativeItems);
        sendPacket(inventoryContentPacket);
    }

    public void sendGamemode() {
        SetPlayerGameTypePacket setPlayerGameTypePacket = new SetPlayerGameTypePacket();
        setPlayerGameTypePacket.setGamemode(cachedEntity.getGameMode().ordinal());
        sendPacket(setPlayerGameTypePacket);

        if(cachedEntity.getGameMode() == GameMode.CREATIVE) {
            sendCreativeInventory();
        }
    }

    public void onTick() {
        //entityCache.getEntities().values().forEach(entity -> entity.onTick(this));
    }

    public RemoteServer getRemoteServer() {
        return remoteServer;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        disconnect("Closed");
    }

    /**
     * Disconnect the player from the proxy and the remote server.
     *
     * @param reason the reason show on the disconnection screen and sent
     *               to the remote server if the player is connected to it
     */
    public void disconnect(String reason) {
        if (!isClosed()) {
            if (downstream != null) {
                downstream.getSession().disconnect(reason);
            }
            bedrockSession.disconnect(reason, false);
        }
    }

    @Override
    public void onDisconnect(@Nonnull DisconnectReason disconnectReason) {
        disconnect("Disconnect");
    }

    @Override
    public void onDisconnect(@Nonnull String s) {
        disconnect("Disconnect");
    }

    /**
     * Queue a packet to be sent to player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendPacket(BedrockPacket packet) {
        if (bedrockSession != null && !bedrockSession.isClosed()) {
            bedrockSession.sendPacket(packet);
        }
    }

    /**
     * Send a packet immediately to the player.
     *
     * @param packet the bedrock packet from the NukkitX protocol lib
     */
    public void sendPacketImmediately(BedrockPacket packet) {
        if (bedrockSession != null && !bedrockSession.isClosed()) {
            bedrockSession.sendPacketImmediately(packet);
        }
    }

    /**
     * Send a packet to the remote server.
     *
     * @param packet the java edition packet from MCProtocolLib
     */
    public void sendRemotePacket(Packet packet) {
        if (downstream != null && downstream.getSession() != null && protocol.getSubProtocol().equals(SubProtocol.GAME)) {
            downstream.getSession().send(packet);
        }
    }

    private class Server {
        @Getter
        private String ip;

        @Getter
        private int port;

        public Server(String server) {
            String[] serverSplit = server.split(":");
            this.ip = serverSplit[0];

            if(serverSplit.length == 2) {
                try {
                    this.port = Integer.parseInt(serverSplit[1]);
                } catch (NumberFormatException e) {
                    this.port = 25565;
                }
            } else {
                this.port = 25565;
            }
        }
    }
}
