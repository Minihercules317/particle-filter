package particlefilter.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.ParticleFilter;
import particlefilter.PfConfig;
import particlefilter.ZoneRenderer;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Edit one zone: name, center (typed, nudged, or moved to you / your crosshair), radius, and the particles it hides.
 * Every change applies and saves immediately, and the zone is outlined in yellow while this screen is open.
 */
public class ZoneEditScreen extends PfScreen {

    private static final int TOP = 136;

    private final PfConfig.Zone zone;
    private List<String> rows = List.of();

    public ZoneEditScreen(Screen parent, PfConfig.Zone zone) {
        super(Component.translatable("particlefilter.zone.title"), parent);
        this.zone = zone;
    }

    @Override
    protected void init() {
        ZoneRenderer.highlighted = zone;
        int left = panelLeft();

        EditBox name = new EditBox(font, left + 50, 28, 200, 20, Component.translatable("particlefilter.zone.name"));
        name.setMaxLength(48);
        name.setValue(zone.name);
        name.setResponder(s -> {
            zone.name = s;
            PfConfig.changed();
        });
        addRenderableWidget(name);

        coordinate(left + 50, 56, zone.x, v -> zone.x = v);
        coordinate(left + 158, 56, zone.y, v -> zone.y = v);
        coordinate(left + 266, 56, zone.z, v -> zone.z = v);

        intField(left + 50, 84, 40, zone.radius, false,
            Component.translatable("particlefilter.radius.tooltip", PfConfig.MIN_RADIUS, PfConfig.MAX_RADIUS), r -> {
                zone.radius = PfConfig.clampRadius(r);
                PfConfig.changed();
            });
        button(Component.translatable("particlefilter.zone.move_to_me"), left + 100, 84, 120, b -> {
            if (minecraft.player != null) moveTo(BlockPos.containing(minecraft.player.position()));
        });
        button(Component.translatable("particlefilter.zone.move_to_crosshair"), left + 224, 84, 120,
            b -> ParticleFilter.targetedBlock().ifPresent(this::moveTo)).active = ParticleFilter.targetedBlock().isPresent();

        int perPage = rowsPerPage(TOP);
        rows = pageOf(List.copyOf(zone.particles), perPage);
        for (int i = 0; i < rows.size(); i++) {
            String entry = rows.get(i);
            button(Component.translatable("particlefilter.unhide"), panelRight() - 64, TOP + i * ROW, 64, b -> {
                PfConfig.setZoneParticle(zone, entry, false);
                rebuildWidgets();
            });
        }
        addPager(zone.particles.size(), perPage);

        int bottom = height - 28;
        button(Component.translatable("particlefilter.zone.delete"), width / 2 - 204, bottom, 200, b -> {
            PfConfig.deleteZone(zone);
            onClose();
        });
        button(Component.translatable("gui.done"), width / 2 + 4, bottom, 200, b -> onClose());
    }

    /** Coordinate field with -/+ nudge buttons on either side. */
    private void coordinate(int x, int y, int value, IntConsumer set) {
        EditBox box = intField(x + 14, y, 54, value, true, Component.translatable("particlefilter.zone.coordinate"), v -> {
            set.accept(v);
            PfConfig.changed();
        });
        button(Component.literal("-"), x - 8, y, 20, b -> box.setValue(Integer.toString(parse(box) - 1)));
        button(Component.literal("+"), x + 70, y, 20, b -> box.setValue(Integer.toString(parse(box) + 1)));
    }

    private static int parse(EditBox box) {
        try {
            return Integer.parseInt(box.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void moveTo(BlockPos pos) {
        zone.x = pos.getX();
        zone.y = pos.getY();
        zone.z = pos.getZ();
        PfConfig.changed();
        rebuildWidgets();
    }

    @Override
    public void removed() {
        ZoneRenderer.highlighted = null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        graphics.drawString(font, Component.translatable("particlefilter.zone.name"), left, 34, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("particlefilter.zone.center"), left, 62, 0xFFFFFF);
        graphics.drawString(font, "X", left + 34, 62, 0xAAAAAA);
        graphics.drawString(font, "Y", left + 142, 62, 0xAAAAAA);
        graphics.drawString(font, "Z", left + 250, 62, 0xAAAAAA);
        graphics.drawString(font, Component.translatable("particlefilter.radius"), left, 90, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("particlefilter.zone.hidden_here"), left, TOP - 14, 0xFFAA00);
        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("particlefilter.zone.nothing_hidden"), left, TOP + 6, 0xAAAAAA);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            String entry = rows.get(i);
            String name = entry.contains(":") ? entry : entry.substring(entry.lastIndexOf('.') + 1);
            graphics.drawString(font, fit(name, 330), left, TOP + i * ROW + 6, entry.contains(":") ? 0x55FFFF : 0xFF55FF);
        }
    }
}
