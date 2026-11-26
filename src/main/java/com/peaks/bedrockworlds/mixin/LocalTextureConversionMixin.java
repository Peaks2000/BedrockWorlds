// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import com.peaks.bedrockworlds.BedrockWorlds;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ResourcePackRewriter.class, remap = false)
public abstract class LocalTextureConversionMixin {
    @Inject(method = "bedrockToJava", at = @At("RETURN"))
    private static void addManagedWorldTextures(ResourcePackStorage storage, CallbackInfoReturnable<Content> callback) {
        BedrockWorlds.convertVanillaTextures(storage, callback.getReturnValue());
    }
}
