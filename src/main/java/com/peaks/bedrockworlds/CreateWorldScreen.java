// SPDX-License-Identifier: GPL-3.0-or-later

package com.peaks.bedrockworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class CreateWorldScreen extends Screen {
    private final WorldListScreen parent;
    private final Set<Path> files = new LinkedHashSet<>();

    private String name = "New World";
    private String seed = "";
    private String mode = "survival";
    private String difficulty = "normal";
    private boolean cheats;
    private String status = "";
    private Button addOns;
    private Button clear;
    private Button create;
    private int left;
    private int top;
    private int panelWidth;

    CreateWorldScreen(WorldListScreen parent) {
        super(Component.literal("Create Bedrock World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(330, width - 20);
        top = Math.max(24, (height - 180) / 2);
        left = (width - panelWidth) / 2;
        int half = (panelWidth - 4) / 2;

        EditBox nameBox = addRenderableWidget(new EditBox(font, left, top + 12, half, 20, Component.literal("World Name")));
        nameBox.setMaxLength(64);
        nameBox.setValue(name);
        nameBox.setResponder(value -> {
            name = value;
            updateButtons();
        });
        EditBox seedBox = addRenderableWidget(new EditBox(font, left + half + 4, top + 12,
            half, 20, Component.literal("Seed")));
        seedBox.setMaxLength(64);
        seedBox.setValue(seed);
        seedBox.setResponder(value -> seed = value);

        addRenderableWidget(CycleButton.builder(CreateWorldScreen::option, mode)
            .withValues("survival", "creative", "adventure")
            .create(left, top + 39, half, 20, Component.literal("Game Mode"), (button, value) -> mode = value));
        addRenderableWidget(CycleButton.builder(CreateWorldScreen::option, difficulty)
            .withValues("peaceful", "easy", "normal", "hard")
            .create(left + half + 4, top + 39, half, 20, Component.literal("Difficulty"),
                (button, value) -> difficulty = value));
        addRenderableWidget(CycleButton.onOffBuilder(cheats)
            .create(left, top + 65, half, 20, Component.literal("Cheats"), (button, value) -> cheats = value));

        addOns = addRenderableWidget(Button.builder(Component.empty(), button -> chooseAddOns())
            .bounds(left, top + 104, panelWidth - 69, 20).build());
        clear = addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
            files.clear();
            status = "";
            updateButtons();
        }).bounds(left + panelWidth - 65, top + 104, 65, 20).build());

        create = addRenderableWidget(Button.builder(Component.literal("Create World"), button -> create())
            .bounds(left, height - 28, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
            .bounds(left + half + 4, height - 28, half, 20).build());
        updateButtons();
    }

    private static Component option(String identifier) {
        return Component.literal(identifier.substring(0, 1).toUpperCase(Locale.ROOT) + identifier.substring(1));
    }

    private void chooseAddOns() {
        minecraft.gui.setScreen(new AddOnsScreen(this, files));
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        for (Path path : paths) {
            Path file = path.toAbsolutePath().normalize();
            if (!isPack(file)) {
                status = "Choose .mcaddon or .mcpack files.";
                return;
            }
        }
        paths.stream().map(Path::toAbsolutePath).map(Path::normalize).forEach(files::add);
        status = "";
        updateButtons();
    }

    static boolean isPack(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(file) && (name.endsWith(".mcaddon") || name.endsWith(".mcpack"));
    }

    private void updateButtons() {
        addOns.setMessage(Component.literal(files.isEmpty() ? "Add-ons…" : "Add-ons… (" + files.size() + " selected)"));
        String tooltip = files.isEmpty() ? "Choose or drag in .mcaddon and .mcpack files."
            : String.join("\n", files.stream().map(path -> path.getFileName().toString()).limit(12).toList());
        addOns.setTooltip(Tooltip.create(Component.literal(tooltip)));
        clear.active = !files.isEmpty();
        if (create != null) {
            create.active = !name.isBlank();
        }
    }

    private void create() {
        var options = new WorldStore.Options(name.strip(), seed.strip(), mode, difficulty, cheats);
        var archives = List.copyOf(files);
        var store = BedrockWorlds.store(minecraft);
        Screen progress = new WorldStatusScreen("Creating Bedrock world…", null);
        minecraft.gui.setScreen(progress);
        BedrockWorlds.WORKER.execute(() -> {
            try {
                store.create(options, archives);
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() == progress) {
                        parent.setStatus("");
                        minecraft.gui.setScreen(parent);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() == progress) {
                        status = exception.getMessage() == null ? "Could not create the Bedrock world." : exception.getMessage();
                        minecraft.gui.setScreen(this);
                    }
                });
            }
        });
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 16, -1);
        graphics.text(font, "World Name", left, top, 0xffdddddd);
        graphics.text(font, "Seed (optional)", left + (panelWidth - 4) / 2 + 4, top, 0xffdddddd);
        graphics.text(font, "Add-ons", left, top + 92, 0xffdddddd);
        int y = top + 129;
        String hint = status.isEmpty() ? "Import server-compatible packs. Marketplace sync is unavailable." : status;
        for (var line : font.split(Component.literal(hint), panelWidth)) {
            graphics.text(font, line, left, y, status.isEmpty() ? 0xffaaaaaa : 0xffffaaaa);
            y += 10;
        }
    }
}
