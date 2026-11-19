package dev.abelinsenmayer.basebuster.client;

import dev.abelinsenmayer.basebuster.network.CreateFactionPayload;
import dev.abelinsenmayer.basebuster.network.CreateFactionResultPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The faction pledge GUI: configures and creates a new scoreboard team. All
 * fields are required before Create Faction enables; the server validates the
 * name and replies via {@link CreateFactionResultPayload} -- success closes
 * the screen, a taken name shows an error that clears when the name is edited.
 */
public class FactionPledgeScreen extends Screen {
    private static final int PANEL_WIDTH = 292;
    private static final int PANEL_HEIGHT = 226;
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int PANEL_EDGE_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_EDGE_DARK = 0xFF555555;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private static final int SWATCH_SIZE = 12;
    private static final int SWATCH_GAP = 4;
    private static final int OPTION_ROW_STEP = 24;

    private int panelLeft;
    private int panelTop;

    private EditBox nameBox;
    private final List<ColorSwatch> swatches = new ArrayList<>();
    private final List<Checkbox> optionCheckboxes = new ArrayList<>();
    private Button createButton;

    // Index into CreateFactionPayload.SELECTABLE_COLORS; -1 = unanswered
    private int selectedColorIndex = -1;
    @Nullable
    private Component errorMessage;
    private boolean awaitingResponse;

    public FactionPledgeScreen() {
        super(Component.translatable("gui.basebuster.faction_pledge"));
    }

    @Override
    protected void init() {
        this.swatches.clear();
        this.optionCheckboxes.clear();
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - PANEL_HEIGHT) / 2;

        // Team name: capped at 50 chars (vanilla team names have no shorter limit)
        this.nameBox = new EditBox(this.font, this.panelLeft + 12, this.panelTop + 22,
                140, 18, Component.translatable("gui.basebuster.faction_pledge.name_hint"));
        this.nameBox.setMaxLength(CreateFactionPayload.MAX_NAME_LENGTH);
        this.nameBox.setHint(Component.translatable("gui.basebuster.faction_pledge.name_hint"));
        // Editing the name clears the "name taken" error and re-checks completeness
        this.nameBox.setResponder(value -> {
            this.errorMessage = null;
            this.updateCreateState();
        });
        addRenderableWidget(this.nameBox);
        setInitialFocus(this.nameBox);

        // One clickable swatch per team color (ChatFormatting's 16); the
        // selected one shows a border
        int swatchY = this.panelTop + 62;
        for (int i = 0; i < CreateFactionPayload.SELECTABLE_COLORS.size(); i++) {
            int x = this.panelLeft + 12 + i * (SWATCH_SIZE + SWATCH_GAP);
            ColorSwatch swatch = new ColorSwatch(x, swatchY, SWATCH_SIZE, i);
            this.swatches.add(swatch);
            addRenderableWidget(swatch);
        }

        // One checkbox per option; the widget draws its own label. Unchecked
        // already means "no", so unlike the yes/no pairs these are always
        // answered and don't gate Create Faction
        String[] optionKeys = {"friendly_fire", "hide_nametags", "show_prefix", "allow_uninvited"};
        int rowY = this.panelTop + 88;
        for (String key : optionKeys) {
            Checkbox checkbox = Checkbox.builder(
                            Component.translatable("gui.basebuster.faction_pledge." + key), this.font)
                    .pos(this.panelLeft + 12, rowY)
                    .maxWidth(PANEL_WIDTH - 24)
                    .build();
            this.optionCheckboxes.add(checkbox);
            addRenderableWidget(checkbox);
            rowY += OPTION_ROW_STEP;
        }

        this.createButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.basebuster.faction_pledge.create"),
                        button -> this.submit())
                .bounds(this.width / 2 - 60, this.panelTop + 196, 120, 20)
                .build());
        this.updateCreateState();
    }

    /** Called when the server's {@link CreateFactionResultPayload} arrives. */
    public void onCreateResult(CreateFactionResultPayload result) {
        this.awaitingResponse = false;
        if (result.success()) {
            this.onClose();
        } else {
            this.errorMessage = result.message();
            this.updateCreateState();
        }
    }

    private void selectColor(int colorIndex, ColorSwatch swatch) {
        this.selectedColorIndex = colorIndex;
        for (ColorSwatch s : this.swatches) {
            s.selected = s == swatch;
        }
        this.updateCreateState();
    }

    /** Create Faction enables only when every field is answered and no request is in flight. */
    private void updateCreateState() {
        boolean complete = !this.nameBox.getValue().trim().isEmpty()
                && this.selectedColorIndex >= 0
                && !this.awaitingResponse;
        this.createButton.active = complete;
    }

    private void submit() {
        this.awaitingResponse = true;
        this.updateCreateState();
        PacketDistributor.sendToServer(new CreateFactionPayload(
                this.nameBox.getValue().trim(),
                this.selectedColorIndex,
                this.optionCheckboxes.get(0).selected(),
                this.optionCheckboxes.get(1).selected(),
                this.optionCheckboxes.get(2).selected(),
                this.optionCheckboxes.get(3).selected()));
    }

    /**
     * Draws the panel, labels, and error before the widgets -- Screen.render
     * paints the background first, so the buttons and box layer on top.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Container-style panel: light edges top/left, dark bottom/right
        graphics.fill(this.panelLeft, this.panelTop,
                this.panelLeft + PANEL_WIDTH, this.panelTop + PANEL_HEIGHT, PANEL_FILL);
        graphics.fill(this.panelLeft, this.panelTop,
                this.panelLeft + PANEL_WIDTH, this.panelTop + 1, PANEL_EDGE_LIGHT);
        graphics.fill(this.panelLeft, this.panelTop,
                this.panelLeft + 1, this.panelTop + PANEL_HEIGHT, PANEL_EDGE_LIGHT);
        graphics.fill(this.panelLeft, this.panelTop + PANEL_HEIGHT - 1,
                this.panelLeft + PANEL_WIDTH, this.panelTop + PANEL_HEIGHT, PANEL_EDGE_DARK);
        graphics.fill(this.panelLeft + PANEL_WIDTH - 1, this.panelTop,
                this.panelLeft + PANEL_WIDTH, this.panelTop + PANEL_HEIGHT, PANEL_EDGE_DARK);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 8, LABEL_COLOR);

        int left = this.panelLeft + 12;
        graphics.drawString(this.font,
                Component.translatable("gui.basebuster.faction_pledge.faction_color"),
                left, this.panelTop + 50, LABEL_COLOR);

        if (this.errorMessage != null) {
            graphics.drawCenteredString(this.font, this.errorMessage.copy().withStyle(ChatFormatting.RED),
                    this.width / 2, this.panelTop + 182, ERROR_COLOR);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A team-colored clickable square. No vanilla swatch widget exists, so this
     * draws fills only -- no textures needed. Selection shows a white border.
     */
    private class ColorSwatch extends AbstractWidget {
        private final int colorIndex;
        private final ChatFormatting chatColor;
        private boolean selected;

        ColorSwatch(int x, int y, int size, int colorIndex) {
            super(x, y, size, size, Component.empty());
            this.colorIndex = colorIndex;
            this.chatColor = CreateFactionPayload.SELECTABLE_COLORS.get(colorIndex);
            // Tooltip names the color in its own color
            setTooltip(Tooltip.create(
                    Component.literal(this.chatColor.getName()).withStyle(this.chatColor)));
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            selectColor(this.colorIndex, this);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x1 = getX();
            int y1 = getY();
            int x2 = x1 + getWidth();
            int y2 = y1 + getHeight();
            graphics.fill(x1, y1, x2, y2, 0xFF000000 | this.chatColor.getColor());
            // Hover outline for every swatch; the picked swatch also gets a
            // persistent white border
            if (this.selected || this.isHovered()) {
                graphics.renderOutline(x1, y1, getWidth(), getHeight(), 0xFFFFFFFF);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
