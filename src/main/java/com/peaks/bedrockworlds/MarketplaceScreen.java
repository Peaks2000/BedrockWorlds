// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.viaversion.viafabricplus.save.SaveManager;
import com.viaversion.viafabricplus.screen.impl.bedrock.BedrockWorldsScreen;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class MarketplaceScreen extends Screen {
    private final Screen parent;
    private List<MarketplaceLibrary.Item> items = List.of();
    private String status = "Loading owned Marketplace packs…";
    private boolean requested;
    private boolean loading;
    private int page;

    MarketplaceScreen(Screen parent) {
        super(Component.literal("My Marketplace Library"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!requested) {
            load();
        }

        int panel = Math.min(330, width - 20);
        int left = (width - panel) / 2;
        int rows = Math.max(1, (height - 117) / 24);
        int pages = Math.max(1, (items.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int index = page * rows; index < Math.min(items.size(), (page + 1) * rows); index++) {
            var item = items.get(index);
            var caption = item.name() + " · " + item.kind();
            var row = Button.builder(Component.literal(font.plainSubstrByWidth(caption, panel - 12)),
                ConfirmLinkScreen.confirmLink(this, item.storePage()))
                .bounds(left, 34 + (index - page * rows) * 24, panel, 20).build();
            row.setTooltip(Tooltip.create(Component.literal(caption)));
            addRenderableWidget(row);
        }

        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page--;
            rebuildWidgets();
        }).bounds(left, height - 52, 28, 20).build()).active = page > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page++;
            rebuildWidgets();
        }).bounds(left + panel - 28, height - 52, 28, 20).build()).active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.literal("Join Bedrock LAN / Friends"),
            button -> BedrockWorldsScreen.INSTANCE.open(this))
            .bounds(left + 32, height - 52, panel - 64, 20).build());
        int half = (panel - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> {
            load();
            rebuildWidgets();
        }).bounds(left, height - 28, half, 20).build()).active = !loading;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
            .bounds(left + half + 4, height - 28, half, 20).build());
    }

    private void load() {
        requested = true;
        items = List.of();
        page = 0;
        var account = SaveManager.INSTANCE.getAccountsSave().getBedrockAccount();
        if (account == null) {
            status = "Link your Bedrock account in ViaFabricPlusPeaks.";
            return;
        }
        status = "Loading owned Marketplace packs…";
        loading = true;
        String language = minecraft.getLanguageManager().getSelected();
        CompletableFuture.runAsync(() -> {
            try {
                var library = MarketplaceLibrary.read(account, language);
                minecraft.execute(() -> {
                    if (account != SaveManager.INSTANCE.getAccountsSave().getBedrockAccount()) {
                        finish(List.of(), "The linked account changed. Select Refresh.");
                    } else {
                        finish(library, library.isEmpty() ? "No owned Marketplace world packs found."
                            : "Open a pack page. Activate purchases in Minecraft Bedrock or a Realm.");
                    }
                });
            } catch (Exception e) {
                String message = e instanceof IOException && e.getMessage() != null
                    && e.getMessage().startsWith("Marketplace ") ? e.getMessage() : "Could not load the Marketplace library.";
                minecraft.execute(() -> finish(List.of(), message));
            }
        });
    }

    private void finish(List<MarketplaceLibrary.Item> library, String message) {
        loading = false;
        items = library;
        status = message;
        if (minecraft.gui.screen() == this) {
            rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, -1);
        int y = height - 77;
        for (var line : font.split(Component.literal(status), Math.max(90, Math.min(330, width - 20) - 10))) {
            graphics.centeredText(font, line, width / 2, y, 0xffdddddd);
            y += 10;
        }
    }
}
