// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class WorldStatusScreen extends Screen {
    private final Runnable cancel;

    WorldStatusScreen(String title, Runnable cancel) {
        super(Component.literal(title));
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        if (cancel != null) {
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(width / 2 - 75, height / 2 + 20, 150, 20).build());
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return cancel != null;
    }

    @Override
    public void onClose() {
        if (cancel != null) {
            cancel.run();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 5, -1);
    }
}
