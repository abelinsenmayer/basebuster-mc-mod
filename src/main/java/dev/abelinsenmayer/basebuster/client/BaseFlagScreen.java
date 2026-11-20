package dev.abelinsenmayer.basebuster.client;

import dev.abelinsenmayer.basebuster.network.CreateFactionPayload;
import dev.abelinsenmayer.basebuster.network.CreateFactionResultPayload;
import dev.abelinsenmayer.basebuster.network.EditBasePayload;
import dev.abelinsenmayer.basebuster.network.EditFactionPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The flag management GUI, with up to two item-icon tabs along the top edge:
 *
 * <ul>
 *   <li><b>Team</b> (a banner matching this flag): configures and creates a
 *   scoreboard team. All fields are required before Create Faction enables;
 *   the server validates the name and replies via
 *   {@link CreateFactionResultPayload} -- success closes the screen, a taken
 *   name shows an error that clears when the name is edited. Opened for an
 *   existing team, fields arrive prefilled, the name shows read-only (teams
 *   can't be renamed), the bottom button reads Save, and the submission
 *   rides {@link EditFactionPayload}.
 *   <li><b>Base</b> (an emerald): flag-specific settings -- the base name,
 *   border visibility, its level, and (eventually) its citizens. Submits via
 *   {@link EditBasePayload}; the server enforces unique base names per team.
 * </ul>
 *
 * <p>Tabs appear only when the screen was opened from a flag; the faction
 * pledge item (no flag) shows just the team tab's contents. From a flag the
 * base tab opens by default.
 */
public class BaseFlagScreen extends Screen {
    /** A faction's current settings, used to prefill the team tab in edit mode. */
    public record Prefill(String name, ChatFormatting color, boolean allowFriendlyFire,
            boolean hideNametags, boolean showNamePrefix) {}

    /**
     * A flag's current base settings, used to prefill the base tab.
     * {@code flagIcon} is the banner stack shown on the team tab, colored and
     * patterned to match the flag.
     */
    public record BaseInfo(@Nullable String name, int baseLevel, boolean showBorder,
            ItemStack flagIcon) {}

    private enum Tab { TEAM, BASE }

    private static final int PANEL_WIDTH = 292;
    private static final int PANEL_HEIGHT = 202;
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int PANEL_FILL_DIM = 0xFF9A9A9A;
    private static final int PANEL_EDGE_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_EDGE_DARK = 0xFF555555;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private static final int TAB_WIDTH = 36;
    private static final int TAB_HEIGHT = 26;
    private static final int TAB_GAP = 4;
    // How far the tabs overlap the panel's top edge so the active tab merges with it
    private static final int TAB_OVERLAP = 2;

    private static final int SWATCH_SIZE = 12;
    private static final int SWATCH_GAP = 4;
    private static final int OPTION_ROW_STEP = 24;

    private int panelLeft;
    private int panelTop;

    // Team tab widgets
    @Nullable
    private EditBox nameBox;
    private final List<ColorSwatch> swatches = new ArrayList<>();
    private final List<Checkbox> optionCheckboxes = new ArrayList<>();
    private Button createButton;
    // Base tab widgets (built only when a flag backs this screen)
    @Nullable
    private EditBox baseNameBox;
    @Nullable
    private Checkbox borderCheckbox;
    @Nullable
    private Button baseSaveButton;
    @Nullable
    private CitizenList citizenList;

    // Index into CreateFactionPayload.SELECTABLE_COLORS; -1 = unanswered
    private int selectedColorIndex = -1;
    @Nullable
    private Component resultMessage;
    private boolean resultIsError;
    private boolean awaitingResponse;
    // Edit mode only: the flag this screen manages; null while creating a
    // faction from a pledge item
    @Nullable
    private final BlockPos flagPos;
    @Nullable
    private final Prefill prefill;
    @Nullable
    private final BaseInfo baseInfo;
    private Tab activeTab;

    /** Create mode: opened from a faction pledge item, so only the team page shows. */
    public BaseFlagScreen() {
        this(null, null, null);
    }

    /**
     * Flag mode: {@code flagPos} is the clicked flag, {@code prefill} carries
     * its owning team's settings, and {@code baseInfo} its base settings. The
     * base tab opens by default; both tabs' bottom buttons read Save.
     */
    public BaseFlagScreen(@Nullable BlockPos flagPos, @Nullable Prefill prefill,
            @Nullable BaseInfo baseInfo) {
        super(Component.translatable(flagPos != null
                ? "gui.basebuster.base_management.title"
                : "gui.basebuster.faction_pledge"));
        this.flagPos = flagPos;
        this.prefill = prefill;
        this.baseInfo = baseInfo;
        this.activeTab = flagPos != null ? Tab.BASE : Tab.TEAM;
    }

    @Override
    protected void init() {
        this.swatches.clear();
        this.optionCheckboxes.clear();
        this.panelLeft = (this.width - PANEL_WIDTH) / 2;
        this.panelTop = (this.height - PANEL_HEIGHT) / 2;

        // Tab strip along the panel's top edge; only flags have a base tab.
        // Order per the mockup: team (banner matching this flag), then base
        // (emerald)
        if (this.flagPos != null && this.baseInfo != null) {
            int stripWidth = 2 * TAB_WIDTH + TAB_GAP;
            int tabX = this.panelLeft + (PANEL_WIDTH - stripWidth) / 2;
            int tabY = this.panelTop - TAB_HEIGHT + TAB_OVERLAP;
            addRenderableWidget(new ItemTab(tabX, tabY, Tab.TEAM,
                    this.baseInfo.flagIcon(),
                    Component.translatable("gui.basebuster.faction_pledge.edit_title")));
            addRenderableWidget(new ItemTab(tabX + TAB_WIDTH + TAB_GAP, tabY, Tab.BASE,
                    new ItemStack(Items.EMERALD),
                    Component.translatable("gui.basebuster.base_management.title")));
        }

        // Team name: capped at 50 chars. Edit mode skips the box entirely --
        // the name is drawn as a label in renderBackground since teams can't
        // be renamed
        if (this.flagPos == null) {
            this.nameBox = new EditBox(this.font, this.panelLeft + 12, this.panelTop + 22,
                    140, 18, Component.translatable("gui.basebuster.faction_pledge.name_hint"));
            this.nameBox.setMaxLength(CreateFactionPayload.MAX_NAME_LENGTH);
            this.nameBox.setHint(Component.translatable("gui.basebuster.faction_pledge.name_hint"));
            // Editing the name clears the "name taken" error and re-checks completeness
            this.nameBox.setResponder(value -> {
                this.resultMessage = null;
                this.updateCreateState();
            });
            addRenderableWidget(this.nameBox);
        }

        // One clickable swatch per team color (ChatFormatting's 16); the
        // selected one shows a border
        if (this.prefill != null) {
            this.selectedColorIndex =
                    CreateFactionPayload.SELECTABLE_COLORS.indexOf(this.prefill.color());
        }
        int swatchY = this.panelTop + 62;
        for (int i = 0; i < CreateFactionPayload.SELECTABLE_COLORS.size(); i++) {
            int x = this.panelLeft + 12 + i * (SWATCH_SIZE + SWATCH_GAP);
            ColorSwatch swatch = new ColorSwatch(x, swatchY, SWATCH_SIZE, i);
            swatch.selected = i == this.selectedColorIndex;
            this.swatches.add(swatch);
            addRenderableWidget(swatch);
        }

        // One checkbox per option; the widget draws its own label. Unchecked
        // already means "no", so unlike the yes/no pairs these are always
        // answered and don't gate Create Faction
        boolean[] preChecked = this.prefill != null
                ? new boolean[]{this.prefill.allowFriendlyFire(), this.prefill.hideNametags(),
                        this.prefill.showNamePrefix()}
                : new boolean[3];
        String[] optionKeys = {"friendly_fire", "hide_nametags", "show_prefix"};
        int rowY = this.panelTop + 88;
        for (int i = 0; i < optionKeys.length; i++) {
            Checkbox checkbox = Checkbox.builder(
                            Component.translatable("gui.basebuster.faction_pledge." + optionKeys[i]), this.font)
                    .pos(this.panelLeft + 12, rowY)
                    .maxWidth(PANEL_WIDTH - 24)
                    .selected(preChecked[i])
                    .build();
            this.optionCheckboxes.add(checkbox);
            addRenderableWidget(checkbox);
            rowY += OPTION_ROW_STEP;
        }

        this.createButton = addRenderableWidget(Button.builder(
                        Component.translatable(this.flagPos != null
                                ? "gui.basebuster.faction_pledge.save"
                                : "gui.basebuster.faction_pledge.create"),
                        button -> this.submit())
                .bounds(this.width / 2 - 60, this.panelTop + 172, 120, 20)
                .build());

        // Base tab: name box, border toggle, Save, and the (currently empty)
        // scrollable citizen list
        if (this.flagPos != null && this.baseInfo != null) {
            this.baseNameBox = new EditBox(this.font, this.panelLeft + 12, this.panelTop + 22,
                    160, 18, Component.translatable("gui.basebuster.base_management.name_hint"));
            this.baseNameBox.setMaxLength(EditBasePayload.MAX_NAME_LENGTH);
            this.baseNameBox.setHint(
                    Component.translatable("gui.basebuster.base_management.name_hint"));
            if (this.baseInfo.name() != null) {
                this.baseNameBox.setValue(this.baseInfo.name());
            }
            this.baseNameBox.setResponder(value -> {
                this.resultMessage = null;
                this.updateBaseSaveState();
            });
            addRenderableWidget(this.baseNameBox);

            this.borderCheckbox = addRenderableWidget(Checkbox.builder(
                            Component.translatable("gui.basebuster.base_management.show_border"), this.font)
                    .pos(this.panelLeft + 12, this.panelTop + 50)
                    .maxWidth(PANEL_WIDTH - 24)
                    .selected(this.baseInfo.showBorder())
                    .build());

            this.baseSaveButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.basebuster.base_management.save"),
                            button -> this.submitBase())
                    .bounds(this.width / 2 - 60, this.panelTop + 76, 120, 20)
                    .build());

            this.citizenList = addRenderableWidget(new CitizenList(
                    this.panelLeft + 12, this.panelTop + 114,
                    PANEL_WIDTH - 24, PANEL_HEIGHT - 114 - 12));
        }

        this.applyTabVisibility();
        this.updateCreateState();
        this.updateBaseSaveState();

        if (this.flagPos == null && this.nameBox != null) {
            setInitialFocus(this.nameBox);
        } else if (this.baseNameBox != null) {
            setInitialFocus(this.baseNameBox);
        }
    }

    /** The faction name: typed text while creating, the team's name while editing. */
    private String nameValue() {
        return this.nameBox != null ? this.nameBox.getValue() : this.prefill.name();
    }

    /**
     * Called when the server's {@link CreateFactionResultPayload} arrives. A
     * failed save shows its message under the tab's button; a successful save
     * shows green feedback and keeps the screen open -- only creating a team
     * from the pledge item (no flag) closes the GUI.
     */
    public void onCreateResult(CreateFactionResultPayload result) {
        this.awaitingResponse = false;
        if (result.success() && this.flagPos == null) {
            this.onClose();
            return;
        }
        this.resultMessage = result.message();
        this.resultIsError = !result.success();
        this.updateCreateState();
        this.updateBaseSaveState();
    }

    private void selectTab(Tab tab) {
        if (tab == this.activeTab) {
            return;
        }
        this.activeTab = tab;
        this.resultMessage = null;
        // Hidden widgets can't be clicked, but a focused edit box would still
        // swallow keystrokes; drop focus when the tab changes
        setFocused(null);
        this.applyTabVisibility();
    }

    /** Shows exactly the widgets belonging to the active tab. */
    private void applyTabVisibility() {
        boolean team = this.activeTab == Tab.TEAM;
        if (this.nameBox != null) {
            this.nameBox.visible = team;
        }
        for (ColorSwatch swatch : this.swatches) {
            swatch.visible = team;
        }
        for (Checkbox checkbox : this.optionCheckboxes) {
            checkbox.visible = team;
        }
        if (this.createButton != null) {
            this.createButton.visible = team;
        }
        if (this.baseNameBox != null) {
            this.baseNameBox.visible = !team;
        }
        if (this.borderCheckbox != null) {
            this.borderCheckbox.visible = !team;
        }
        if (this.baseSaveButton != null) {
            this.baseSaveButton.visible = !team;
        }
        if (this.citizenList != null) {
            this.citizenList.visible = !team;
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
        boolean complete = !nameValue().trim().isEmpty()
                && this.selectedColorIndex >= 0
                && !this.awaitingResponse;
        this.createButton.active = complete;
    }

    /** The base tab's Save enables only on a non-empty name and no request in flight. */
    private void updateBaseSaveState() {
        if (this.baseSaveButton != null && this.baseNameBox != null) {
            this.baseSaveButton.active =
                    !this.baseNameBox.getValue().trim().isEmpty() && !this.awaitingResponse;
        }
    }

    private void submit() {
        this.awaitingResponse = true;
        this.updateCreateState();
        boolean allowFriendlyFire = this.optionCheckboxes.get(0).selected();
        boolean hideNametags = this.optionCheckboxes.get(1).selected();
        boolean showNamePrefix = this.optionCheckboxes.get(2).selected();
        if (this.flagPos != null) {
            PacketDistributor.sendToServer(new EditFactionPayload(
                    this.flagPos, this.selectedColorIndex,
                    allowFriendlyFire, hideNametags, showNamePrefix));
        } else {
            PacketDistributor.sendToServer(new CreateFactionPayload(
                    nameValue().trim(), this.selectedColorIndex,
                    allowFriendlyFire, hideNametags, showNamePrefix));
        }
    }

    private void submitBase() {
        if (this.flagPos == null || this.baseNameBox == null || this.borderCheckbox == null) {
            return;
        }
        this.awaitingResponse = true;
        this.updateBaseSaveState();
        PacketDistributor.sendToServer(new EditBasePayload(
                this.flagPos, this.baseNameBox.getValue().trim(), this.borderCheckbox.selected()));
    }

    /**
     * Draws the panel, labels, and error before the widgets -- Screen.render
     * paints the background first, so the buttons and boxes layer on top.
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

        int left = this.panelLeft + 12;
        if (this.activeTab == Tab.TEAM) {
            graphics.drawCenteredString(this.font, this.flagPos != null
                            ? Component.translatable("gui.basebuster.faction_pledge.edit_title")
                            : this.title,
                    this.width / 2, this.panelTop + 8, LABEL_COLOR);

            if (this.prefill != null) {
                // The faction name, read-only and in the faction's own color
                Integer color = this.prefill.color().getColor();
                graphics.drawString(this.font, this.prefill.name(), left, this.panelTop + 26,
                        color != null ? 0xFF000000 | color : LABEL_COLOR);
            }
            graphics.drawString(this.font,
                    Component.translatable("gui.basebuster.faction_pledge.faction_color"),
                    left, this.panelTop + 50, LABEL_COLOR);

            if (this.resultMessage != null) {
                graphics.drawCenteredString(this.font, this.styledResult(),
                        this.width / 2, this.panelTop + 158, ERROR_COLOR);
            }
        } else {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.basebuster.base_management.title"),
                    this.width / 2, this.panelTop + 8, LABEL_COLOR);

            // Base level, top right beside the name box
            Component levelText = Component.translatable(
                    "gui.basebuster.base_management.level", this.baseInfo.baseLevel());
            graphics.drawString(this.font, levelText,
                    this.panelLeft + PANEL_WIDTH - 12 - this.font.width(levelText),
                    this.panelTop + 26, LABEL_COLOR);

            if (this.resultMessage != null) {
                graphics.drawCenteredString(this.font, this.styledResult(),
                        this.width / 2, this.panelTop + 102, ERROR_COLOR);
            }
        }
    }

    /** The server reply, tinted red for failures and green for saved changes. */
    private Component styledResult() {
        return this.resultMessage.copy()
                .withStyle(this.resultIsError ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A container-style tab along the panel's top edge, labeled by an item
     * icon instead of text. The active tab shares the panel's fill so the two
     * read as one surface; inactive tabs darken and show a bottom edge.
     */
    private class ItemTab extends AbstractWidget {
        private final Tab tab;
        private final ItemStack icon;

        ItemTab(int x, int y, Tab tab, ItemStack icon, Component tooltipText) {
            super(x, y, TAB_WIDTH, TAB_HEIGHT, Component.empty());
            this.tab = tab;
            this.icon = icon;
            setTooltip(Tooltip.create(tooltipText));
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            selectTab(this.tab);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = this.tab == activeTab;
            int x1 = getX();
            int y1 = getY();
            int x2 = x1 + getWidth();
            int y2 = y1 + getHeight();
            graphics.fill(x1, y1, x2, y2, selected ? PANEL_FILL : PANEL_FILL_DIM);
            graphics.fill(x1, y1, x2, y1 + 1, PANEL_EDGE_LIGHT);
            graphics.fill(x1, y1, x1 + 1, y2, PANEL_EDGE_LIGHT);
            graphics.fill(x2 - 1, y1, x2, y2, PANEL_EDGE_DARK);
            if (selected) {
                // Erase the bottom edge so the tab flows into the panel
                graphics.fill(x1 + 1, y2 - TAB_OVERLAP, x2 - 1, y2, PANEL_FILL);
            } else {
                graphics.fill(x1, y2 - 1, x2, y2, PANEL_EDGE_DARK);
                if (this.isHovered()) {
                    graphics.renderOutline(x1, y1, getWidth(), getHeight() - TAB_OVERLAP, 0xFFFFFFFF);
                }
            }
            graphics.renderFakeItem(this.icon,
                    x1 + (getWidth() - 16) / 2, y1 + (getHeight() - 16) / 2 - 1);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * The scrollable area that will hold citizen info. Empty for now -- it
     * draws only its frame via {@link AbstractScrollWidget}.
     */
    private static class CitizenList extends AbstractScrollWidget {
        CitizenList(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        @Override
        protected int getInnerHeight() {
            return 0;
        }

        @Override
        protected double scrollRate() {
            return 9.0;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // No citizen info yet
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
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
