// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import com.peaks.bedrockworlds.BedrockWorlds;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class LocalResourceConnectionMixin {
    @Shadow @Final protected Connection connection;
    @Shadow @Final protected ServerData serverData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bindManagedResources(CallbackInfo callback) {
        BedrockWorlds.bindResourceConnection(serverData, connection);
    }
}
