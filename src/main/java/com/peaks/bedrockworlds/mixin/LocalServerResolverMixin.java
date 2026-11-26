// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import com.peaks.bedrockworlds.BedrockWorlds;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerNameResolver.class, priority = 1100)
public abstract class LocalServerResolverMixin {
    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void resolveManagedLocalServer(ServerAddress address,
        CallbackInfoReturnable<Optional<ResolvedServerAddress>> callback) {
        var local = BedrockWorlds.localServerAddress(address.getHost(), address.getPort());
        if (local != null) {
            callback.setReturnValue(Optional.of(ResolvedServerAddress.from(local)));
        }
    }
}
