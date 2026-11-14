// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldSelectionList.class)
abstract class WorldSelectionListMixin {
    @Redirect(method = "handleNewLevels", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;openFresh(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;)V"))
    private void keepWorldTabsVisible(Minecraft client, Runnable onCancel) {
        // With no Java saves, vanilla otherwise skips the singleplayer screen entirely.
    }
}
