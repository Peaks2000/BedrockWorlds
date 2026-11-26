// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.io.IOException;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class WorldListScreen extends Screen {
    private final Screen parent;
    private int page;
    private String status = "";

    public WorldListScreen(Screen parent) {
        super(Component.literal("Bedrock Worlds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(330, width - 20);
        int left = (width - panelWidth) / 2;
        int rows = Math.max(1, (height - 110) / 24);
        int footer = height - 28;
        List<WorldStore.World> worlds;
        try {
            worlds = BedrockWorlds.store(minecraft).list();
        } catch (IOException exception) {
            worlds = List.of();
            setStatus("Could not read the Bedrock worlds folder.");
        }
        int pages = Math.max(1, (worlds.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        int count = Math.min(rows, worlds.size() - page * rows);
        for (int row = 0; row < count; row++) {
            WorldStore.World world = worlds.get(page * rows + row);
            String name = world.options().name();
            Button item = Button.builder(Component.literal(font.plainSubstrByWidth(name, panelWidth - 12)),
                    button -> openWorld(world))
                .bounds(left, 34 + row * 24, panelWidth, 20).build();
            item.setTooltip(Tooltip.create(Component.literal(name + " · " + world.options().gameMode())));
            addRenderableWidget(item);
        }
        if (worlds.isEmpty() && status.isEmpty()) {
            setStatus("No Bedrock worlds yet.");
        }

        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
            .bounds(left, footer - 24, 28, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
            .bounds(left + panelWidth - 28, footer - 24, 28, 20).build());
        previous.active = page > 0;
        next.active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.literal("Marketplace"), button -> {
            CreateWorldScreen create = new CreateWorldScreen(this);
            minecraft.gui.setScreen(new MarketplaceScreen(this, create::selectMarketplace));
        }).bounds(left + 32, footer - 24, panelWidth - 64, 20).build());
        int half = (panelWidth - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Create New World"),
            button -> minecraft.gui.setScreen(new CreateWorldScreen(this))).bounds(left, footer, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
            .bounds(left + half + 4, footer, half, 20).build());
    }

    private void changePage(int change) {
        page += change;
        rebuildWidgets();
    }

    void setStatus(String status) {
        this.status = status;
    }

    private void openWorld(WorldStore.World world) {
        setStatus("");
        try {
            ServerRuntime runtime = ServerRuntime.current();
            if (runtime.installed(BedrockWorlds.store(minecraft).root())) {
                BedrockWorlds.start(minecraft, this, world, false);
                return;
            }
            String setup = runtime == ServerRuntime.MAC
                ? " Start Docker Desktop first. The server runs in a local Linux container."
                : "";
            minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
                minecraft.gui.setScreen(this);
                if (accepted) {
                    BedrockWorlds.start(minecraft, this, world, true);
                }
            }, Component.literal("Install Bedrock Dedicated Server?"),
                Component.literal("Download Bedrock Dedicated Server " + ServerRuntime.VERSION
                    + " from Mojang. Continue if you agree to the Minecraft EULA and Privacy Statement." + setup),
                Component.literal("Agree and Download"), CommonComponents.GUI_CANCEL) {
                    @Override
                    protected void addAdditionalText() {
                        layout.addChild(Button.builder(Component.literal("Review Mojang terms"),
                            ConfirmLinkScreen.confirmLink(this, ServerRuntime.DOWNLOAD_PAGE)).width(160).build());
                    }
                });
        } catch (IOException exception) {
            setStatus(exception.getMessage());
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
        int y = height - 67;
        for (var line : font.split(Component.literal(status), width - 32)) {
            graphics.centeredText(font, line, width / 2, y, 0xffdddddd);
            y += 10;
        }
    }
}
