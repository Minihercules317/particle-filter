package particlefilter.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import particlefilter.PfConfig;
import particlefilter.PfRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything currently hidden, in one list: particles hidden everywhere, particles hidden per zone (with the zone),
 * and blocks whose breaking/mining particles are hidden. Also lets you hide something by typing its id.
 */
public class HiddenScreen extends PfScreen {

    private static final int TOP = 62;

    private enum Scope { EVERYWHERE, ZONE, BLOCK }

    private record Row(String entry, Scope scope, PfConfig.Zone zone) {}

    private List<Row> rows = List.of();
    private EditBox input;

    public HiddenScreen(Screen parent) {
        super(Component.translatable("particlefilter.hidden.title"), parent);
    }

    @Override
    protected void init() {
        String typed = input != null ? input.getValue() : "";
        input = new EditBox(font, panelLeft(), 28, 186, 20, Component.translatable("particlefilter.hidden.input"));
        input.setMaxLength(256);
        input.setHint(Component.translatable("particlefilter.hidden.input.hint"));
        input.setValue(typed);
        addRenderableWidget(input);
        button(Component.translatable("particlefilter.hidden.add_everywhere"), panelLeft() + 190, 28, 104, b -> {
            String entry = input.getValue().trim();
            if (!entry.isEmpty()) PfConfig.setParticleHidden(entry, true);
            input.setValue("");
            rebuildWidgets();
        });
        button(Component.translatable("particlefilter.hidden.add_block"), panelLeft() + 298, 28, 104, b -> {
            String entry = input.getValue().trim();
            if (!entry.isEmpty()) PfConfig.setBlockHidden(entry, true);
            input.setValue("");
            rebuildWidgets();
        });

        List<Row> all = new ArrayList<>();
        PfConfig.hiddenParticles().forEach(e -> all.add(new Row(e, Scope.EVERYWHERE, null)));
        for (PfConfig.Zone zone : PfConfig.zones()) {
            zone.particles.forEach(e -> all.add(new Row(e, Scope.ZONE, zone)));
        }
        PfConfig.hiddenBlocks().forEach(e -> all.add(new Row(e, Scope.BLOCK, null)));

        int perPage = rowsPerPage(TOP);
        rows = pageOf(all, perPage);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = TOP + i * ROW;
            if (row.scope == Scope.ZONE && isHere(row.zone)) {
                button(Component.translatable("particlefilter.edit"), panelRight() - 112, y, 44,
                    b -> minecraft.setScreen(new ZoneEditScreen(this, row.zone)));
            }
            button(Component.translatable("particlefilter.unhide"), panelRight() - 64, y, 64, b -> {
                switch (row.scope) {
                    case EVERYWHERE -> PfConfig.setParticleHidden(row.entry, false);
                    case ZONE -> PfConfig.setZoneParticle(row.zone, row.entry, false);
                    case BLOCK -> PfConfig.setBlockHidden(row.entry, false);
                }
                rebuildWidgets();
            });
        }
        addPager(all.size(), perPage);
        button(Component.translatable("gui.back"), width / 2 - 100, height - 28, 200, b -> onClose());
    }

    private static boolean isHere(PfConfig.Zone zone) {
        return zone.world.equals(PfRules.currentWorld()) && zone.dimension.equals(PfRules.currentDimension());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("particlefilter.hidden.empty"), width / 2, TOP + 6, 0xAAAAAA);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = TOP + i * ROW + 6;
            String name = row.scope == Scope.BLOCK || row.entry.contains(":") ? row.entry : row.entry.substring(row.entry.lastIndexOf('.') + 1);
            graphics.drawString(font, fit(name, 170), panelLeft(), y, row.scope == Scope.BLOCK ? 0xFFFF55 : 0x55FFFF);
            String scope = switch (row.scope) {
                case EVERYWHERE -> Component.translatable("particlefilter.everywhere").getString();
                case BLOCK -> Component.translatable("particlefilter.hidden.scope_block").getString();
                case ZONE -> Component.translatable(isHere(row.zone) ? "particlefilter.hidden.scope_zone" : "particlefilter.hidden.scope_zone_elsewhere",
                    row.zone.name).getString();
            };
            graphics.drawString(font, fit(scope, 112), panelLeft() + 176, y, 0xAAAAAA);
        }
    }
}
