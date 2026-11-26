// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.peaks.bedrockworlds.BedrockWorlds;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerNameResolver.class)
public abstract class LocalServerResolverMixin {
    // A Bedrock HEAD injector can cancel resolution before another HEAD injector runs.
    @WrapMethod(method = "resolveAddress")
    private Optional<ResolvedServerAddress> resolveManagedLocalServer(ServerAddress address,
        Operation<Optional<ResolvedServerAddress>> original) {
        var local = BedrockWorlds.localServerAddress(address.getHost(), address.getPort());
        if (local != null) {
            return Optional.of(ResolvedServerAddress.from(local));
        }
        return original.call(address);
    }
}
