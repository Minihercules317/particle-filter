package particlefilter.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.PfConfig;
import particlefilter.ZoneRenderer;

import java.util.List;

/** Zones in the current world and dimension, with Edit/Delete, plus creating a new zone where you stand. */
public class ZonesScreen extends PfScreen {

    private static final int TOP = 34;

    private List<PfConfig.Zone> rows = List.of();

    public ZonesScreen(Screen parent) {
        super(Component.translatable("particlefilter.zones.title"), parent);
    }

    @Override
    protected void init() {
        List<PfConfig.Zone> all = PfConfig.zonesHere();
        int perPage = rowsPerPage(TOP);
        rows = pageOf(all, perPage);
        for (int i = 0; i < rows.size(); i++) {
            PfConfig.Zone zone = rows.get(i);
            int y = TOP + i * ROW;
            button(Component.translatable("particlefilter.edit"), panelRight() - 116, y, 50, b -> minecraft.setScreen(new ZoneEditScreen(this, zone)));
            button(Component.translatable("particlefilter.delete"), panelRight() - 62, y, 62, b -> {
                PfConfig.deleteZone(zone);
                rebuildWidgets();
            });
        }
        addPager(all.size(), perPage);

        int bottom = height - 28;
        button(Component.translatable("particlefilter.zones.new"), width / 2 - 204, bottom, 134, b -> {
            if (minecraft.player == null) return;
            PfConfig.Zone zone = PfConfig.createZone(BlockPos.containing(minecraft.player.position()), 8);
            if (zone != null) minecraft.setScreen(new ZoneEditScreen(this, zone));
        });
        button(HubScreen.outlinesLabel(), width / 2 - 66, bottom, 134, b -> {
            ZoneRenderer.showOutlines = !ZoneRenderer.showOutlines;
            b.setMessage(HubScreen.outlinesLabel());
        });
        button(Component.translatable("gui.back"), width / 2 + 72, bottom, 132, b -> onClose());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("particlefilter.zones.empty"), width / 2, TOP + 6, 0xAAAAAA);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            PfConfig.Zone zone = rows.get(i);
            int y = TOP + i * ROW + 6;
            graphics.drawString(font, fit(zone.name, 110), panelLeft(), y, 0xFFAA00);
            String details = zone.x + ", " + zone.y + ", " + zone.z + "  ±" + zone.radius + "  "
                + Component.translatable("particlefilter.zones.count", zone.particles.size()).getString();
            graphics.drawString(font, fit(details, 170), panelLeft() + 114, y, 0xAAAAAA);
        }
    }
}
