// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import com.mojang.blaze3d.Blaze3D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class AddOnsScreen extends Screen {
    private final CreateWorldScreen parent;
    private final Set<Path> selection;
    private final Path folder;
    private int page;
    private String status = "Drop packs here or choose packs from the folder.";

    AddOnsScreen(CreateWorldScreen parent, Set<Path> selection) {
        super(Component.literal("Add-ons"));
        this.parent = parent;
        this.selection = selection;
        folder = BedrockWorlds.store(minecraft).root().resolve("Add-ons");
    }

    @Override
    protected void init() {
        var packs = new LinkedHashSet<>(selection);
        try (var paths = Files.list(Files.createDirectories(folder))) {
            paths.filter(CreateWorldScreen::isPack).sorted().forEach(packs::add);
        } catch (IOException exception) {
            status = "Could not read the Add-ons folder.";
        }
        var files = List.copyOf(packs);
        int panel = Math.min(330, width - 20);
        int left = (width - panel) / 2;
        int rows = Math.max(1, (height - 108) / 24);
        int pages = Math.max(1, (files.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        int end = Math.min(files.size(), (page + 1) * rows);
        for (int index = page * rows; index < end; index++) {
            Path file = files.get(index);
            String name = file.getFileName().toString();
            String caption = (selection.contains(file) ? "[x] " : "[ ] ") + name;
            var choice = Button.builder(Component.literal(font.plainSubstrByWidth(caption, panel - 12)),
                button -> {
                    if (!selection.remove(file)) {
                        selection.add(file);
                    }
                    rebuildWidgets();
                }).bounds(left, 33 + (index - page * rows) * 24, panel, 20).build();
            choice.setTooltip(Tooltip.create(Component.literal(name)));
            addRenderableWidget(choice);
        }
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page--;
            rebuildWidgets();
        }).bounds(left, height - 52, 28, 20).build());
        var next = addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page++;
            rebuildWidgets();
        }).bounds(left + panel - 28, height - 52, 28, 20).build());
        previous.active = page > 0;
        next.active = page + 1 < pages;
        int buttonWidth = (panel - 12) / 4;
        addRenderableWidget(Button.builder(Component.literal("Open Folder"),
            button -> Blaze3D.openPath(folder)).bounds(left, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> rebuildWidgets())
            .bounds(left + buttonWidth + 4, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Marketplace"),
            button -> minecraft.gui.setScreen(new MarketplaceScreen(this, parent::selectMarketplace)))
            .bounds(left + (buttonWidth + 4) * 2, height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
            .bounds(left + (buttonWidth + 4) * 3, height - 28, buttonWidth, 20).build());
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        parent.onFilesDrop(paths);
        status = paths.stream().allMatch(CreateWorldScreen::isPack)
            ? "Dropped packs selected." : "Choose .mcaddon or .mcpack files.";
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, -1);
        graphics.centeredText(font, status, width / 2, height - 66, 0xffdddddd);
    }
}
