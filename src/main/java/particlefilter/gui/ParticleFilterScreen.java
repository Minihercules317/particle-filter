package particlefilter.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.List;

/**
 * The Particle Filter window, with three tabs:
 * - Scan: record which particles spawn around what you're looking at, and tick them off everywhere or in a zone
 * - Hidden: everything currently hidden and where, with a box to hide something by id
 * - Zones: the zones in this world, to edit, delete or create
 */
public class ParticleFilterScreen extends PfScreen {

    public enum Tab { SCAN, HIDDEN, ZONES }

    private static final int TABS_Y = 6;
    private static final int TOP = 32;
    private static final int FOOTER_HEIGHT = 34;

    private static Tab lastTab = Tab.SCAN;

    private Tab tab;
    private RowList list;
    /** Scroll position carried across rebuilds of the same tab. */
    private double listScroll;
    private Button scanButton;
    /** Set by callbacks of widgets inside list rows; the rebuild then happens on the next tick, outside the click. */
    private boolean refreshPending;
    private int shownScanGeneration;
    private EditBox idInput;
    private String idInputText = "";
    /** Zone whose Delete button was pressed once and now waits for confirmation. */
    private Zone confirmingDelete;

    public ParticleFilterScreen(Screen parent, Tab tab) {
        super(Component.translatable("particlefilter.title"), parent);
        this.tab = tab != null ? tab : lastTab;
    }

    @Override
    protected void init() {
        lastTab = tab;
        addTabs();
        switch (tab) {
            case SCAN -> initScan();
            case HIDDEN -> initHidden();
            case ZONES -> initZones();
        }
        button(Component.translatable("gui.done"), contentLeft() + contentWidth() - 100, height - 28, 100, b -> onClose());
    }

    private void addTabs() {
        int w = (contentWidth() - 8) / 3;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button b = button(tabLabel(t), contentLeft() + i * (w + 4), TABS_Y, w, btn -> switchTo(t));
            b.active = t != tab;
        }
    }

    private Component tabLabel(Tab t) {
        return switch (t) {
            case SCAN -> Component.translatable("particlefilter.tab.scan");
            case HIDDEN -> Component.translatable("particlefilter.tab.hidden", hiddenCount());
            case ZONES -> Component.translatable("particlefilter.tab.zones", PfConfig.zonesHere().size());
        };
    }

    private static int hiddenCount() {
        return PfConfig.hiddenParticles().size() + PfConfig.hiddenBlocks().size()
            + PfConfig.zones().stream().mapToInt(z -> z.particles.size()).sum();
    }

    private void switchTo(Tab t) {
        tab = t;
        confirmingDelete = null;
        listScroll = 0;
        ZoneRenderer.highlighted = null;
        ZoneRenderer.preview = null;
        rebuildWidgets();
    }

    /** Rebuilds the current tab after a change, keeping the list scrolled where it was. */
    private void refresh() {
        if (list != null) listScroll = list.getScrollAmount();
        rebuildWidgets();
    }

    private void refreshNextTick() {
        refreshPending = true;
    }

    private void setRows(List<RowList.Row> rows) {
        list.setRows(rows);
        list.setScrollAmount(listScroll);
    }

    private RowList addList(int top) {
        list = new RowList(minecraft, width, height - FOOTER_HEIGHT - top, top, ROW_HEIGHT, contentWidth());
        return addRenderableWidget(list);
    }

    // ---------------------------------------------------------------- Scan

    private int everywhereColumn() {
        return contentWidth() - 118;
    }

    private int zoneColumn() {
        return contentWidth() - 44;
    }

    private void initScan() {
        scanButton = button(scanLabel(), contentLeft(), TOP, 110, b -> {
            ParticleScan.start();
            refresh();
        });
        scanButton.active = !ParticleScan.isRunning();
        addList(TOP + 40);
        setRows(scanRows());
        shownScanGeneration = ParticleScan.generation();

        Zone target = scanTargetZone();
        Button edit = button(Component.translatable("particlefilter.scan.edit_zone"), contentLeft() + contentWidth() - 184, height - 28, 80,
            b -> minecraft.setScreen(new ZoneEditScreen(this, scanTargetZone())));
        edit.active = target != null;
    }

    private Component scanLabel() {
        return ParticleScan.isRunning()
            ? Component.translatable("particlefilter.scan.running", ParticleScan.secondsLeft())
            : Component.translatable("particlefilter.scan.button");
    }

    private List<RowList.Row> scanRows() {
        List<RowList.Row> rows = new ArrayList<>();
        BlockPos target = ParticleScan.target();
        for (ParticleScan.Result result : ParticleScan.results()) {
            String particle = result.particle();
            String name = fit(ParticleScan.displayName(particle), everywhereColumn() - 70);
            int color = result.typed() ? 0x55FFFF : 0xFF77FF;
            RowList.Row row = new RowList.Row((g, left, top, w, hovered) -> {
                g.drawString(font, name, left + 4, top + 8, color);
                String count = "×" + result.count();
                g.drawString(font, count, left + everywhereColumn() - 16 - font.width(count), top + 8, GRAY);
            });

            Checkbox everywhere = Checkbox.builder(Component.empty(), font)
                .selected(PfConfig.isHiddenEverywhere(particle))
                .onValueChange((cb, hidden) -> PfConfig.setHiddenEverywhere(particle, hidden))
                .build();
            everywhere.setTooltip(Tooltip.create(Component.translatable("particlefilter.scan.everywhere.tooltip", ParticleScan.displayName(particle))));
            row.add(everywhere, everywhereColumn());

            Checkbox inZone = Checkbox.builder(Component.empty(), font)
                .selected(target != null && PfConfig.isHiddenInZoneAt(target, particle))
                .onValueChange((cb, hidden) -> {
                    PfConfig.setHiddenInZoneAt(target, particle, hidden);
                    refreshNextTick(); // may have created the zone
                })
                .build();
            inZone.setTooltip(Tooltip.create(Component.translatable("particlefilter.scan.zone.tooltip", ParticleScan.displayName(particle))));
            inZone.active = target != null;
            row.add(inZone, zoneColumn());
            rows.add(row);
        }
        return rows;
    }

    private static Zone scanTargetZone() {
        BlockPos target = ParticleScan.target();
        return target == null ? null : PfConfig.zoneAt(target);
    }

    private void renderScan(GuiGraphics g) {
        int left = contentLeft();
        // Status next to the Scan button.
        Component status;
        if (ParticleScan.isRunning()) status = Component.translatable("particlefilter.scan.status_running", ParticleScan.targetName(), ParticleScan.RADIUS);
        else if (ParticleScan.target() != null) status = Component.translatable(ParticleScan.results().size() == 1
            ? "particlefilter.scan.status_done_one" : "particlefilter.scan.status_done", ParticleScan.results().size(), ParticleScan.targetName());
        else status = Component.translatable("particlefilter.scan.status_idle");
        g.drawString(font, fit(status.getString(), contentWidth() - 120), left + 118, TOP + 6, WHITE);

        if (list.isEmpty()) {
            if (!ParticleScan.isRunning()) drawCenteredHint(g, Component.translatable(ParticleScan.target() == null
                ? "particlefilter.scan.empty_first" : "particlefilter.scan.empty_none", ParticleScan.RADIUS), TOP + 60);
        } else {
            int headerY = TOP + 28;
            g.drawString(font, Component.translatable("particlefilter.scan.col_particle"), left + 4, headerY, GRAY);
            g.drawCenteredString(font, Component.translatable("particlefilter.scan.col_everywhere"), left + everywhereColumn() + 8, headerY, GRAY);
            g.drawCenteredString(font, Component.translatable("particlefilter.scan.col_zone"), left + zoneColumn() + 8, headerY, GRAY);
        }

        // Which zone "Hide in zone" uses, and preview of the one it would create.
        BlockPos target = ParticleScan.target();
        Zone zone = scanTargetZone();
        ZoneRenderer.highlighted = zone;
        ZoneRenderer.preview = null;
        Component zoneText = null;
        if (zone != null) {
            zoneText = Component.translatable("particlefilter.scan.zone_existing", zone.name, zone.sizeText());
        } else if (target != null) {
            Zone wouldCreate = PfConfig.defaultZoneAround(target);
            if (wouldCreate != null) {
                ZoneRenderer.preview = wouldCreate.box();
                zoneText = Component.translatable("particlefilter.scan.zone_new", wouldCreate.sizeText());
            }
        }
        if (zoneText != null) g.drawString(font, fit(zoneText.getString(), contentWidth() - 192), left, height - 22, zone != null ? 0xFFFF55 : 0x55FFFF);
    }

    // ---------------------------------------------------------------- Hidden

    private enum Scope { EVERYWHERE, ZONE, BLOCK }

    private void initHidden() {
        int left = contentLeft();
        idInput = new EditBox(font, left, TOP, contentWidth() - 204, BUTTON_HEIGHT, Component.translatable("particlefilter.hidden.input"));
        idInput.setMaxLength(256);
        idInput.setHint(Component.translatable("particlefilter.hidden.input_hint").withStyle(ChatFormatting.DARK_GRAY));
        idInput.setValue(idInputText);
        idInput.setResponder(s -> idInputText = s);
        addRenderableWidget(idInput);
        button(Component.translatable("particlefilter.hidden.add_particle"), Component.translatable("particlefilter.hidden.add_particle.tooltip"),
            left + contentWidth() - 200, TOP, 98, b -> addTyped(false));
        button(Component.translatable("particlefilter.hidden.add_block"), Component.translatable("particlefilter.hidden.add_block.tooltip"),
            left + contentWidth() - 98, TOP, 98, b -> addTyped(true));

        addList(TOP + 40);
        setRows(hiddenRows());
    }

    private void addTyped(boolean block) {
        String id = idInputText.trim();
        if (id.isEmpty()) return;
        if (block) PfConfig.setBlockHidden(id, true);
        else PfConfig.setHiddenEverywhere(id, true);
        idInputText = "";
        refresh();
    }

    private List<RowList.Row> hiddenRows() {
        List<RowList.Row> rows = new ArrayList<>();
        PfConfig.hiddenParticles().forEach(p -> rows.add(hiddenRow(p, Scope.EVERYWHERE, null)));
        for (Zone zone : PfConfig.zones()) zone.particles.forEach(p -> rows.add(hiddenRow(p, Scope.ZONE, zone)));
        PfConfig.hiddenBlocks().forEach(b -> rows.add(hiddenRow(b, Scope.BLOCK, null)));
        return rows;
    }

    private RowList.Row hiddenRow(String entry, Scope scope, Zone zone) {
        int w = contentWidth();
        String name = fit(scope == Scope.BLOCK ? entry : ParticleScan.displayName(entry), w / 2 - 20);
        int nameColor = scope == Scope.BLOCK ? 0xFFFF77 : entry.contains(":") ? 0x55FFFF : 0xFF77FF;
        String where = fit(switch (scope) {
            case EVERYWHERE -> Component.translatable("particlefilter.hidden.where_everywhere").getString();
            case BLOCK -> Component.translatable("particlefilter.hidden.where_block").getString();
            case ZONE -> Component.translatable(PfConfig.isInCurrentWorld(zone)
                ? "particlefilter.hidden.where_zone" : "particlefilter.hidden.where_zone_elsewhere", zone.name).getString();
        }, w / 2 - 130);
        int whereColor = scope == Scope.ZONE ? 0xFFAA00 : GRAY;
        RowList.Row row = new RowList.Row((g, left, top, rw, hovered) -> {
            g.drawString(font, name, left + 4, top + 8, nameColor);
            g.drawString(font, where, left + w / 2, top + 8, whereColor);
            if (hovered && zone != null && PfConfig.isInCurrentWorld(zone)) ZoneRenderer.highlighted = zone;
        });
        if (scope == Scope.ZONE && PfConfig.isInCurrentWorld(zone)) {
            row.add(Button.builder(Component.translatable("particlefilter.edit"), b -> minecraft.setScreen(new ZoneEditScreen(this, zone)))
                .size(44, BUTTON_HEIGHT).build(), w - 116);
        }
        row.add(Button.builder(Component.translatable("particlefilter.unhide"), b -> {
            switch (scope) {
                case EVERYWHERE -> PfConfig.setHiddenEverywhere(entry, false);
                case ZONE -> PfConfig.setHiddenInZone(zone, entry, false);
                case BLOCK -> PfConfig.setBlockHidden(entry, false);
            }
            refreshNextTick();
        }).size(68, BUTTON_HEIGHT).build(), w - 70);
        return row;
    }

    private void renderHidden(GuiGraphics g) {
        int left = contentLeft();
        if (list.isEmpty()) {
            drawCenteredHint(g, Component.translatable("particlefilter.hidden.empty"), TOP + 60);
        } else {
            g.drawString(font, Component.translatable("particlefilter.hidden.col_hidden"), left + 4, TOP + 28, GRAY);
            g.drawString(font, Component.translatable("particlefilter.hidden.col_where"), left + contentWidth() / 2, TOP + 28, GRAY);
        }
    }

    // ---------------------------------------------------------------- Zones

    private void initZones() {
        addList(TOP + 14);
        List<RowList.Row> rows = new ArrayList<>();
        int w = contentWidth();
        for (Zone zone : PfConfig.zonesHere()) {
            String name = fit(zone.name, 120);
            String size = zone.sizeText();
            String at = zone.x + ", " + zone.y + ", " + zone.z;
            String count = Integer.toString(zone.particles.size());
            RowList.Row row = new RowList.Row((g, left, top, rw, hovered) -> {
                g.drawString(font, name, left + 4, top + 8, 0xFFAA00);
                g.drawString(font, size, left + 128, top + 8, WHITE);
                g.drawString(font, fit(at, w - 360), left + 192, top + 8, GRAY);
                g.drawCenteredString(font, count, left + w - 136, top + 8, WHITE);
                if (hovered) ZoneRenderer.highlighted = zone;
            });
            row.add(Button.builder(Component.translatable("particlefilter.edit"), b -> minecraft.setScreen(new ZoneEditScreen(this, zone)))
                .size(44, BUTTON_HEIGHT).build(), w - 116);
            boolean confirming = zone == confirmingDelete;
            row.add(Button.builder(Component.translatable(confirming ? "particlefilter.confirm" : "particlefilter.delete")
                    .withStyle(confirming ? ChatFormatting.RED : ChatFormatting.RESET), b -> {
                if (confirmingDelete == zone) {
                    PfConfig.deleteZone(zone);
                    confirmingDelete = null;
                } else {
                    confirmingDelete = zone;
                }
                refreshNextTick();
            }).size(68, BUTTON_HEIGHT).build(), w - 70);
            rows.add(row);
        }
        setRows(rows);

        int left = contentLeft();
        button(Component.translatable("particlefilter.zones.new"), Component.translatable("particlefilter.zones.new.tooltip"),
            left, height - 28, 130, b -> {
                if (minecraft.player == null) return;
                Zone zone = PfConfig.createZone(minecraft.player.blockPosition().above(PfConfig.NEW_ZONE_DEPTH_BELOW));
                if (zone != null) minecraft.setScreen(new ZoneEditScreen(this, zone));
            });
        Checkbox outlines = Checkbox.builder(Component.translatable("particlefilter.zones.always_show"), font)
            .pos(left + 138, height - 28)
            .selected(ZoneRenderer.alwaysShow)
            .onValueChange((cb, on) -> ZoneRenderer.alwaysShow = on)
            .build();
        outlines.setTooltip(Tooltip.create(Component.translatable("particlefilter.zones.always_show.tooltip")));
        addRenderableWidget(outlines);
    }

    private void renderZones(GuiGraphics g) {
        int left = contentLeft();
        if (list.isEmpty()) {
            drawCenteredHint(g, Component.translatable("particlefilter.zones.empty"), TOP + 40);
            return;
        }
        int headerY = TOP + 2;
        g.drawString(font, Component.translatable("particlefilter.zones.col_name"), left + 4, headerY, GRAY);
        g.drawString(font, Component.translatable("particlefilter.zones.col_size"), left + 128, headerY, GRAY);
        g.drawString(font, Component.translatable("particlefilter.zones.col_center"), left + 192, headerY, GRAY);
        g.drawCenteredString(font, Component.translatable("particlefilter.zones.col_hides"), left + contentWidth() - 136, headerY, GRAY);
    }

    // ----------------------------------------------------------------

    @Override
    public void tick() {
        if (refreshPending) {
            refreshPending = false;
            refresh();
            return;
        }
        if (tab != Tab.SCAN) return;
        if (ParticleScan.generation() != shownScanGeneration) refresh(); // a scan finished: show its results
        else if (ParticleScan.isRunning()) scanButton.setMessage(scanLabel());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (tab != Tab.SCAN) ZoneRenderer.highlighted = null; // rows set it again while hovered
        super.render(graphics, mouseX, mouseY, partialTick);
        switch (tab) {
            case SCAN -> renderScan(graphics);
            case HIDDEN -> renderHidden(graphics);
            case ZONES -> renderZones(graphics);
        }
    }
}
