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
package org.dragonet.proxy.network.translator.java.entity;

import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityDestroyPacket;
import lombok.extern.log4j.Log4j2;
import org.dragonet.proxy.network.session.ProxySession;
import org.dragonet.proxy.network.session.cache.object.CachedEntity;
import org.dragonet.proxy.network.session.cache.object.CachedPlayer;
import org.dragonet.proxy.network.translator.misc.PacketTranslator;
import org.dragonet.proxy.util.registry.PacketRegisterInfo;

@Log4j2
@PacketRegisterInfo(packet = ServerEntityDestroyPacket.class)
public class PCEntityDestroyTranslator extends PacketTranslator<ServerEntityDestroyPacket> {

    @Override
    public void translate(ProxySession session, ServerEntityDestroyPacket packet) {
        for(int entityId : packet.getEntityIds()) {
            CachedEntity cachedEntity = session.getEntityCache().getByRemoteId(entityId);
            if(cachedEntity == null) {
                //log.warn("EntityDestroy: Cached entity doesn't exist");
                return;
            }

            if(cachedEntity instanceof CachedPlayer && session.getPlayerListCache().getPlayerInfo().get(cachedEntity.getJavaUuid()) != null) {
                cachedEntity.despawn(session);
                return;
            }

            log.trace("Destroying entity with proxy eid: " + cachedEntity.getProxyEid());
            cachedEntity.destroy(session);
        }
    }
}
