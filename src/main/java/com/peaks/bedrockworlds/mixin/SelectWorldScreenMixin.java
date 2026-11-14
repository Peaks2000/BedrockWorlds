// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds.mixin;

import com.peaks.bedrockworlds.WorldListScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(SelectWorldScreen.class)
abstract class SelectWorldScreenMixin extends Screen {
    protected SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/EditBox;setResponder(Ljava/util/function/Consumer;)V"),
        locals = LocalCapture.CAPTURE_FAILHARD)
    private void addBedrockWorldsButton(CallbackInfo ci, LinearLayout header, LinearLayout searchRow) {
        searchRow.addChild(Button.builder(Component.literal("Bedrock Worlds"),
            button -> minecraft.gui.setScreen(new WorldListScreen(this))).width(108).build());
    }
}
