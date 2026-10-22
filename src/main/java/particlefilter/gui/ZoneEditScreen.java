package particlefilter.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.ParticleFilter;
import particlefilter.config.PfConfig;
import particlefilter.config.Zone;
import particlefilter.render.ZoneRenderer;
import particlefilter.scan.ParticleScan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Edits one zone: name, position (center X/Z, bottom Y), size (width/height/length), and the particles it hides.
 * Changes apply and save immediately; the zone is outlined in yellow in the world while this screen is open.
 */
public class ZoneEditScreen extends PfScreen {

    private static final int COORD_LIMIT = 30_000_000;

    private final Zone zone;
    private RowList list;
    private boolean confirmingDelete;
    private boolean refreshPending;

    public ZoneEditScreen(Screen parent, Zone zone) {
        super(Component.translatable("particlefilter.zone.title"), parent);
        this.zone = zone;
    }

    @Override
    protected void init() {
        if (zone == null) {
            onClose();
            return;
        }
        int left = contentLeft();
        int column = contentWidth() / 3;

        EditBox name = new EditBox(font, left + 40, 22, contentWidth() - 40, BUTTON_HEIGHT, Component.translatable("particlefilter.zone.name"));
        name.setMaxLength(48);
        name.setValue(zone.name);
        name.setResponder(s -> {
            zone.name = s;
            PfConfig.changed();
        });
        addRenderableWidget(name);

        // Position row, then size row; labels are drawn above each spinner in render().
        int positionY = 70;
        spinner(left, positionY, zone.x, -COORD_LIMIT, COORD_LIMIT, Component.translatable("particlefilter.zone.center_x.tooltip"), set(v -> zone.x = v));
        spinner(left + column, positionY, zone.y, -COORD_LIMIT, COORD_LIMIT, Component.translatable("particlefilter.zone.bottom_y.tooltip"), set(v -> zone.y = v));
        spinner(left + 2 * column, positionY, zone.z, -COORD_LIMIT, COORD_LIMIT, Component.translatable("particlefilter.zone.center_z.tooltip"), set(v -> zone.z = v));

        int sizeY = 118;
        Component sizeTip = Component.translatable("particlefilter.zone.size.tooltip", Zone.MIN_SIZE, Zone.MAX_SIZE);
        spinner(left, sizeY, zone.width, Zone.MIN_SIZE, Zone.MAX_SIZE, sizeTip, set(v -> zone.width = v));
        spinner(left + column, sizeY, zone.height, Zone.MIN_SIZE, Zone.MAX_SIZE, sizeTip, set(v -> zone.height = v));
        spinner(left + 2 * column, sizeY, zone.length, Zone.MIN_SIZE, Zone.MAX_SIZE, sizeTip, set(v -> zone.length = v));

        int moveY = 146;
        int half = (contentWidth() - 4) / 2;
        button(Component.translatable("particlefilter.zone.center_on_me"), Component.translatable("particlefilter.zone.center_on_me.tooltip"),
            left, moveY, half, b -> {
                if (minecraft.player == null) return;
                BlockPos feet = minecraft.player.blockPosition();
                moveTo(feet.getX(), feet.getY(), feet.getZ());
            });
        Button toCrosshair = button(Component.translatable("particlefilter.zone.center_on_crosshair"), Component.translatable("particlefilter.zone.center_on_crosshair.tooltip"),
            left + half + 4, moveY, half, b -> ParticleFilter.targetedBlock().ifPresent(pos -> moveTo(pos.getX(), pos.getY() - zone.height / 2, pos.getZ())));
        toCrosshair.active = ParticleFilter.targetedBlock().isPresent();

        int listTop = 186;
        list = addRenderableWidget(new RowList(minecraft, width, height - 34 - listTop, listTop, ROW_HEIGHT, contentWidth()));
        List<RowList.Row> rows = new ArrayList<>();
        for (String particle : zone.particles) {
            String shown = fit(ParticleScan.displayName(particle), contentWidth() - 90);
            int color = particle.contains(":") ? 0x55FFFF : 0xFF77FF;
            rows.add(new RowList.Row((g, l, top, w, hovered) -> g.drawString(font, shown, l + 4, top + 8, color))
                .add(Button.builder(Component.translatable("particlefilter.unhide"), b -> {
                    PfConfig.setHiddenInZone(zone, particle, false);
                    refreshPending = true;
                }).size(68, BUTTON_HEIGHT).build(), contentWidth() - 70));
        }
        list.setRows(rows);

        button(Component.translatable(confirmingDelete ? "particlefilter.zone.delete_confirm" : "particlefilter.zone.delete")
                .withStyle(confirmingDelete ? ChatFormatting.RED : ChatFormatting.RESET),
            left, height - 28, 130, b -> {
                if (confirmingDelete) {
                    PfConfig.deleteZone(zone);
                    onClose();
                } else {
                    confirmingDelete = true;
                    rebuildWidgets();
                }
            });
        button(Component.translatable("gui.done"), left + contentWidth() - 100, height - 28, 100, b -> onClose());
    }

    /** Applies a spinner value to the zone and saves. */
    private IntConsumer set(IntConsumer field) {
        return v -> {
            field.accept(v);
            PfConfig.changed();
        };
    }

    private void moveTo(int x, int y, int z) {
        zone.x = x;
        zone.y = y;
        zone.z = z;
        PfConfig.changed();
        rebuildWidgets(); // refresh the spinners
    }

    @Override
    public void tick() {
        if (refreshPending) {
            refreshPending = false;
            rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ZoneRenderer.highlighted = zone;
        super.render(g, mouseX, mouseY, partialTick);
        if (zone == null) return;
        int left = contentLeft();
        int column = contentWidth() / 3;
        g.drawCenteredString(font, title, width / 2, 8, WHITE);
        g.drawString(font, Component.translatable("particlefilter.zone.name"), left, 28, WHITE);

        g.drawString(font, Component.translatable("particlefilter.zone.position"), left, 48, 0xFFAA00);
        label(g, "particlefilter.zone.center_x", left, 60);
        label(g, "particlefilter.zone.bottom_y", left + column, 60);
        label(g, "particlefilter.zone.center_z", left + 2 * column, 60);

        g.drawString(font, Component.translatable("particlefilter.zone.size", zone.sizeText()), left, 96, 0xFFAA00);
        label(g, "particlefilter.zone.width", left, 108);
        label(g, "particlefilter.zone.height", left + column, 108);
        label(g, "particlefilter.zone.length", left + 2 * column, 108);

        g.drawString(font, Component.translatable("particlefilter.zone.hides", zone.particles.size()), left, 174, 0xFFAA00);
        if (list.isEmpty()) drawCenteredHint(g, Component.translatable("particlefilter.zone.hides_nothing"), 200);
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawCenteredString(font, Component.translatable(key), x + 50, y, GRAY);
    }
}
