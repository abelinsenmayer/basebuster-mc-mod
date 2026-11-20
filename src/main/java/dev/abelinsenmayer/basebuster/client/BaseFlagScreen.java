package dev.abelinsenmayer.basebuster.client;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.network.BanishCitizenPayload;
import dev.abelinsenmayer.basebuster.network.CreateFactionPayload;
import dev.abelinsenmayer.basebuster.network.CreateFactionResultPayload;
import dev.abelinsenmayer.basebuster.network.EditBasePayload;
import dev.abelinsenmayer.basebuster.network.EditFactionPayload;
import dev.abelinsenmayer.basebuster.network.ManageRepairsPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
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
 *   border visibility, its level, and a scrollable roster of its citizens.
 *   Each citizen row shows a miniature villager, its name and profession,
 *   and a Banish button that ejects it as a citizenship certificate via
 *   {@link BanishCitizenPayload}. The rest submits via {@link EditBasePayload};
 *   the server enforces unique base names per team.
 *   <li><b>Repairs</b> (an iron repair hammer): the flag's saved repair
 *   targets in a scrollable list under pinned column headers. Each row shows
 *   the destroyed block's item icon, its coordinates, the icon of whatever
 *   currently obstructs the spot (a snapshot taken when the row is built),
 *   and Abandon/Repair buttons that apply immediately via
 *   {@link ManageRepairsPayload}. A pinned Repair all button restores every
 *   target at once.
 * </ul>
 *
 * <p>Tabs appear only when the screen was opened from a flag; the faction
 * founding document (no flag) shows just the team tab's contents. From a
 * flag the base tab opens by default.
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

    private enum Tab { TEAM, BASE, REPAIRS }

    private static final int PANEL_WIDTH = 340;
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
    // Repairs tab widgets
    @Nullable
    private Button abandonAllButton;
    @Nullable
    private Button repairAllButton;
    @Nullable
    private RepairList repairList;

    // Index into CreateFactionPayload.SELECTABLE_COLORS; -1 = unanswered
    private int selectedColorIndex = -1;
    @Nullable
    private Component resultMessage;
    private boolean resultIsError;
    private boolean awaitingResponse;
    // Edit mode only: the flag this screen manages; null while creating a
    // faction from a founding document
    @Nullable
    private final BlockPos flagPos;
    @Nullable
    private final Prefill prefill;
    @Nullable
    private final BaseInfo baseInfo;
    private Tab activeTab;
    // Live citizen roster stats pulled from the synced flag each tick for
    // the base tab's counter; -1 until the first tick resolves the entity
    private int citizenCount = -1;
    private int citizenCapacity = -1;

    /** Create mode: opened from a faction founding document, so only the team page shows. */
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

        // Tab strip along the panel's top edge; only flags have the extra
        // tabs. Order: team (banner matching this flag), base (emerald),
        // repairs (iron hammer)
        if (this.flagPos != null && this.baseInfo != null) {
            int stripWidth = 3 * TAB_WIDTH + 2 * TAB_GAP;
            int tabX = this.panelLeft + (PANEL_WIDTH - stripWidth) / 2;
            int tabY = this.panelTop - TAB_HEIGHT + TAB_OVERLAP;
            addRenderableWidget(new ItemTab(tabX, tabY, Tab.TEAM,
                    this.baseInfo.flagIcon(),
                    Component.translatable("gui.basebuster.faction_pledge.edit_title")));
            addRenderableWidget(new ItemTab(tabX + TAB_WIDTH + TAB_GAP, tabY, Tab.BASE,
                    new ItemStack(Items.EMERALD),
                    Component.translatable("gui.basebuster.base_management.title")));
            addRenderableWidget(new ItemTab(tabX + 2 * (TAB_WIDTH + TAB_GAP), tabY, Tab.REPAIRS,
                    new ItemStack(BaseBuster.IRON_REPAIR_HAMMER.get()),
                    Component.translatable("gui.basebuster.repairs.title")));
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

            // Repairs tab: pinned header with column labels on top and the
            // bulk Abandon/Repair buttons right-aligned beneath them, then a
            // scrollable list of repair rows (the column offsets must agree
            // with RepairEntry.render and renderBackground)
            int bulkY = this.panelTop + 26;
            this.repairAllButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.basebuster.repairs.repair_all"),
                            button -> PacketDistributor.sendToServer(new ManageRepairsPayload(
                                    this.flagPos, ManageRepairsPayload.Action.REPAIR_ALL,
                                    BlockPos.ZERO)))
                    .bounds(this.panelLeft + PANEL_WIDTH - 12 - 76, bulkY, 76, 20)
                    .build());
            this.abandonAllButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.basebuster.repairs.abandon_all"),
                            button -> PacketDistributor.sendToServer(new ManageRepairsPayload(
                                    this.flagPos, ManageRepairsPayload.Action.ABANDON_ALL,
                                    BlockPos.ZERO)))
                    .bounds(this.panelLeft + PANEL_WIDTH - 12 - 76 - 4 - 84, bulkY, 84, 20)
                    .build());
            this.repairList = addRenderableWidget(new RepairList(
                    this.panelLeft + 12, this.panelTop + 50,
                    PANEL_WIDTH - 24, PANEL_HEIGHT - 50 - 12));
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
     * from the founding document (no flag) closes the GUI.
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
        boolean base = this.activeTab == Tab.BASE;
        boolean repairs = this.activeTab == Tab.REPAIRS;
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
            this.baseNameBox.visible = base;
        }
        if (this.borderCheckbox != null) {
            this.borderCheckbox.visible = base;
        }
        if (this.baseSaveButton != null) {
            this.baseSaveButton.visible = base;
        }
        if (this.citizenList != null) {
            this.citizenList.visible = base;
        }
        if (this.repairAllButton != null) {
            this.repairAllButton.visible = repairs;
        }
        if (this.abandonAllButton != null) {
            this.abandonAllButton.visible = repairs;
        }
        if (this.repairList != null) {
            this.repairList.visible = repairs;
        }
    }

    /**
     * Pulls the flag's repair list once per tick so rows appear and disappear
     * as the block entity syncs from the server, and keeps Repair all disabled
     * while the list is empty.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.flagPos == null || this.repairList == null
                || this.minecraft == null || this.minecraft.level == null
                || !(this.minecraft.level.getBlockEntity(this.flagPos)
                        instanceof BaseFlagBlockEntity flagEntity)) {
            return;
        }
        this.repairList.refresh(flagEntity.getRepairTargets());
        boolean hasRepairs = !flagEntity.getRepairTargets().isEmpty();
        this.repairAllButton.active = hasRepairs;
        this.abandonAllButton.active = hasRepairs;
        if (this.citizenList != null) {
            this.citizenList.refresh(flagEntity.getCitizens());
        }
        this.citizenCount = flagEntity.getCitizens().size();
        this.citizenCapacity = flagEntity.getCitizenCapacity();
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
        } else if (this.activeTab == Tab.BASE) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.basebuster.base_management.title"),
                    this.width / 2, this.panelTop + 8, LABEL_COLOR);

            // Base level, top right beside the name box
            Component levelText = Component.translatable(
                    "gui.basebuster.base_management.level", this.baseInfo.baseLevel());
            graphics.drawString(this.font, levelText,
                    this.panelLeft + PANEL_WIDTH - 12 - this.font.width(levelText),
                    this.panelTop + 26, LABEL_COLOR);

            // Citizens counter above the roster; the synced entity supplies
            // live values once the first tick resolves it
            if (this.citizenCapacity >= 0) {
                graphics.drawString(this.font,
                        Component.translatable("gui.basebuster.base_management.citizens",
                                this.citizenCount, this.citizenCapacity),
                        left, this.panelTop + 102, LABEL_COLOR);
            }

            if (this.resultMessage != null) {
                graphics.drawCenteredString(this.font, this.styledResult(),
                        this.width / 2, this.panelTop + 102, ERROR_COLOR);
            }
        } else {
            // Repairs tab: pinned column headers above the scrollable list,
            // on the row above the bulk Abandon/Repair buttons. The offsets
            // match the columns in RepairEntry.render
            int listLeft = this.panelLeft + 12;
            int headerY = this.panelTop + 14;
            graphics.drawString(this.font,
                    Component.translatable("gui.basebuster.repairs.column_block"),
                    listLeft + 6, headerY, LABEL_COLOR);
            graphics.drawString(this.font,
                    Component.translatable("gui.basebuster.repairs.column_location"),
                    listLeft + 40, headerY, LABEL_COLOR);
            graphics.drawString(this.font,
                    Component.translatable("gui.basebuster.repairs.column_obstruction"),
                    listLeft + 150, headerY, LABEL_COLOR);
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

    private static final int ROW_HEIGHT = 24;
    private static final int ROW_BUTTON_WIDTH = 58;
    // Citizen rows are taller: the miniature villager needs the room
    private static final int CITIZEN_ROW_HEIGHT = 32;

    /**
     * The scrollable list of the flag's saved repairs. The pinned headers sit
     * above it in {@link #renderBackground}; this widget holds only the rows.
     * Rows rebuild whenever the synced {@link BaseFlagBlockEntity} repair list
     * changes -- a repair or abandon applied server-side arrives on the next
     * block-entity update.
     */
    private class RepairList extends ContainerObjectSelectionList<RepairEntry> {
        RepairList(int x, int y, int width, int height) {
            super(BaseFlagScreen.this.minecraft, width, height, y, ROW_HEIGHT);
            this.setX(x);
        }

        /** Rebuilds the rows when {@code targets} no longer matches them. */
        void refresh(List<BaseFlagBlockEntity.RepairTarget> targets) {
            boolean same = children().size() == targets.size();
            for (int i = 0; same && i < targets.size(); i++) {
                same = children().get(i).target.pos().equals(targets.get(i).pos());
            }
            if (same) {
                return;
            }
            double scroll = getScrollAmount();
            clearEntries();
            for (BaseFlagBlockEntity.RepairTarget target : targets) {
                addEntry(new RepairEntry(target));
            }
            setScrollAmount(scroll);
        }

        // Vanilla caps entries at 220px and centers them; use the full list
        // width instead, leaving room for the scrollbar on the right
        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        // The panel supplies the list's background; suppress the vanilla dark
        // texture and separator lines so rows sit on the panel's fill
        @Override
        protected void renderListBackground(GuiGraphics graphics) {}

        @Override
        protected void renderListSeparators(GuiGraphics graphics) {}
    }

    /**
     * One repair row: the destroyed block's item icon, its coordinates, the
     * obstructing block's icon (an empty stack when the spot is air), and
     * Abandon/Repair buttons. The obstruction is a snapshot taken when the
     * row is built -- the tab doesn't live-update it.
     */
    private class RepairEntry extends ContainerObjectSelectionList.Entry<RepairEntry> {
        private final BaseFlagBlockEntity.RepairTarget target;
        private final ItemStack blockIcon;
        private final ItemStack obstructionIcon;
        private final Button abandonButton;
        private final Button repairButton;
        private final List<Button> buttons;

        RepairEntry(BaseFlagBlockEntity.RepairTarget target) {
            this.target = target;
            this.blockIcon = iconFor(target.state());
            this.obstructionIcon = BaseFlagScreen.this.minecraft.level != null
                    ? iconFor(BaseFlagScreen.this.minecraft.level.getBlockState(target.pos()))
                    : ItemStack.EMPTY;
            this.abandonButton = Button.builder(
                            Component.translatable("gui.basebuster.repairs.abandon"),
                            button -> PacketDistributor.sendToServer(new ManageRepairsPayload(
                                    flagPos, ManageRepairsPayload.Action.ABANDON, target.pos())))
                    .size(ROW_BUTTON_WIDTH, 20)
                    .build();
            this.repairButton = Button.builder(
                            Component.translatable("gui.basebuster.repairs.repair"),
                            button -> PacketDistributor.sendToServer(new ManageRepairsPayload(
                                    flagPos, ManageRepairsPayload.Action.REPAIR, target.pos())))
                    .size(ROW_BUTTON_WIDTH, 20)
                    .build();
            this.buttons = List.of(this.abandonButton, this.repairButton);
        }

        /** The block's item icon, or an empty stack for blocks without one. */
        private static ItemStack iconFor(net.minecraft.world.level.block.state.BlockState state) {
            return state.isAir() || state.getBlock().asItem() == Items.AIR
                    ? ItemStack.EMPTY
                    : new ItemStack(state.getBlock());
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean hovered, float partialTick) {
            // Row panel, inset so it reads as a card inside the list
            graphics.fill(left + 2, top, left + width - 2, top + height - 2,
                    hovered ? PANEL_FILL : PANEL_FILL_DIM);
            graphics.renderOutline(left + 2, top, width - 4, height - 2, PANEL_EDGE_DARK);

            int iconY = top + (height - 2 - 16) / 2;
            if (!this.blockIcon.isEmpty()) {
                graphics.renderFakeItem(this.blockIcon, left + 6, iconY);
            }
            BlockPos pos = this.target.pos();
            graphics.drawString(font,
                    pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
                    left + 34, top + (height - 2 - 9) / 2, LABEL_COLOR, false);
            if (!this.obstructionIcon.isEmpty()) {
                graphics.renderFakeItem(this.obstructionIcon, left + 150, iconY);
            }

            this.abandonButton.setX(left + width - 4 - 2 * ROW_BUTTON_WIDTH - 4);
            this.abandonButton.setY(top + 1);
            this.abandonButton.render(graphics, mouseX, mouseY, partialTick);
            this.repairButton.setX(left + width - 4 - ROW_BUTTON_WIDTH);
            this.repairButton.setY(top + 1);
            this.repairButton.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.buttons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.buttons;
        }
    }

    /**
     * The scrollable citizen roster. Rows are rebuilt only when the synced
     * roster changes so a rebuild mid-scroll doesn't lose position.
     */
    private class CitizenList extends ContainerObjectSelectionList<CitizenEntry> {
        CitizenList(int x, int y, int width, int height) {
            super(BaseFlagScreen.this.minecraft, width, height, y, CITIZEN_ROW_HEIGHT);
            this.setX(x);
        }

        void refresh(List<BaseFlagBlockEntity.Citizen> citizens) {
            boolean same = children().size() == citizens.size();
            for (int i = 0; same && i < citizens.size(); i++) {
                same = children().get(i).citizen.equals(citizens.get(i));
            }
            if (same) {
                return;
            }
            double scroll = getScrollAmount();
            clearEntries();
            for (BaseFlagBlockEntity.Citizen citizen : citizens) {
                addEntry(new CitizenEntry(citizen));
            }
            setScrollAmount(scroll);
        }

        // Vanilla caps entries at 220px and centers them; use the full list
        // width instead, leaving room for the scrollbar on the right
        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        // The panel supplies the list's background; suppress the vanilla dark
        // texture and separator lines so rows sit on the panel's fill
        @Override
        protected void renderListBackground(GuiGraphics graphics) {}

        @Override
        protected void renderListSeparators(GuiGraphics graphics) {}
    }

    /**
     * One citizen row: a miniature rendering of the villager (dressed for
     * the roster's recorded profession), its name and profession, and a
     * Banish button that ejects the villager as a citizenship certificate
     * dropped at the flag.
     */
    private class CitizenEntry extends ContainerObjectSelectionList.Entry<CitizenEntry> {
        private final BaseFlagBlockEntity.Citizen citizen;
        private final Button banishButton;
        @Nullable
        private Villager displayVillager;

        CitizenEntry(BaseFlagBlockEntity.Citizen citizen) {
            this.citizen = citizen;
            this.banishButton = Button.builder(
                            Component.translatable("gui.basebuster.base_management.banish"),
                            button -> PacketDistributor.sendToServer(new BanishCitizenPayload(
                                    flagPos, citizen.id())))
                    .size(ROW_BUTTON_WIDTH, 20)
                    .build();
        }

        /**
         * A display-only villager standing in for the (possibly unloaded or
         * far-away) citizen, dressed in its recorded profession. Built once
         * per row like the repair list's obstruction snapshot.
         */
        @Nullable
        private Villager displayVillager() {
            if (this.displayVillager == null) {
                if (BaseFlagScreen.this.minecraft == null
                        || BaseFlagScreen.this.minecraft.level == null) {
                    return null;
                }
                Villager villager = EntityType.VILLAGER.create(BaseFlagScreen.this.minecraft.level);
                if (villager == null) {
                    return null;
                }
                VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION
                        .get(this.citizen.profession());
                if (profession != null) {
                    villager.setVillagerData(villager.getVillagerData().setProfession(profession));
                }
                this.displayVillager = villager;
            }
            return this.displayVillager;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, boolean hovered, float partialTick) {
            // Row panel, inset so it reads as a card inside the list
            graphics.fill(left + 2, top, left + width - 2, top + height - 2,
                    hovered ? PANEL_FILL : PANEL_FILL_DIM);
            graphics.renderOutline(left + 2, top, width - 4, height - 2, PANEL_EDGE_DARK);

            Villager villager = this.displayVillager();
            if (villager != null) {
                // The inventory-style renderer, shrunk into a slot beside the text
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                        left + 4, top, left + 30, top + height - 2,
                        14, 0.0625F, mouseX, mouseY, villager);
            }

            int textY = top + (height - 2 - 9) / 2;
            int textX = left + 36;
            int textRight = left + width - 4 - ROW_BUTTON_WIDTH - 8;
            String name = font.plainSubstrByWidth(this.citizen.name(), textRight - textX);
            graphics.drawString(font, name, textX, textY, LABEL_COLOR, false);
            if (!"none".equals(this.citizen.profession().getPath())) {
                Component profession = Component.translatable(
                        "entity." + this.citizen.profession().getNamespace()
                                + ".villager." + this.citizen.profession().getPath());
                int professionX = textX + font.width(name) + 6;
                int maxProfessionWidth = textRight - professionX;
                if (maxProfessionWidth > 0) {
                    graphics.drawString(font,
                            font.plainSubstrByWidth(profession.getString(), maxProfessionWidth),
                            professionX, textY, 0xFF787878, false);
                }
            }

            this.banishButton.setX(left + width - 4 - ROW_BUTTON_WIDTH);
            this.banishButton.setY(top + (height - 2 - 20) / 2 + 1);
            this.banishButton.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.banishButton);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.banishButton);
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
