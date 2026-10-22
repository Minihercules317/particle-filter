package particlefilter.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import particlefilter.Census;
import particlefilter.PfConfig;
import particlefilter.ZoneRenderer;

import java.util.List;

/**
 * Results of the last scan, each with Everywhere and Here toggles. "Here" means the smallest zone containing the
 * scanned block, or a new zone centered on it (previewed in cyan) if none contains it yet.
 */
public class ScanScreen extends PfScreen {

    private static final int TOP = 44;
    /** Radius for zones created from this screen; remembered between scans. */
    private static int newZoneRadius = 8;

    private List<Census.Result> rows = List.of();
    private PfConfig.Zone target;

    public ScanScreen(Screen parent) {
        super(Component.translatable("particlefilter.scan.title"), parent);
    }

    @Override
    protected void init() {
        BlockPos center = Census.lastCenter();
        target = center == null ? null : PfConfig.zoneAt(center);
        updatePreview(center);

        int perPage = rowsPerPage(TOP + 14);
        List<Census.Result> all = Census.lastResults();
        rows = pageOf(all, perPage);
        int everywhereX = width / 2 + 20;
        int hereX = width / 2 + 112;
        for (int i = 0; i < rows.size(); i++) {
            Census.Result row = rows.get(i);
            int y = TOP + i * ROW;
            button(state(PfConfig.isParticleHidden(row.entry())), everywhereX, y, 88, b -> {
                PfConfig.setParticleHidden(row.entry(), !PfConfig.isParticleHidden(row.entry()));
                b.setMessage(state(PfConfig.isParticleHidden(row.entry())));
            });
            Button here = button(state(center != null && PfConfig.isHiddenInZoneAt(center, row.entry())), hereX, y, 88, b -> {
                PfConfig.toggleHiddenAt(center, row.entry(), newZoneRadius);
                rebuildWidgets(); // the target zone may have just been created
            });
            here.active = center != null;
        }
        addPager(all.size(), perPage);

        int bottom = height - 28;
        int radius = target != null ? target.radius : newZoneRadius;
        intField(panelLeft() + 72, bottom, 36, radius, false,
            Component.translatable("particlefilter.radius.tooltip", PfConfig.MIN_RADIUS, PfConfig.MAX_RADIUS), r -> {
                r = PfConfig.clampRadius(r);
                if (target != null) {
                    target.radius = r;
                    PfConfig.changed();
                } else {
                    newZoneRadius = r;
                    ZoneRenderer.previewRadius = r;
                }
            });
        Button edit = button(Component.translatable("particlefilter.scan.edit_zone"), width / 2 - 92, bottom, 90,
            b -> minecraft.setScreen(new ZoneEditScreen(this, target)));
        edit.active = target != null;
        button(Component.translatable("particlefilter.hub.hidden_short"), width / 2 + 2, bottom, 90,
            b -> minecraft.setScreen(new HiddenScreen(this)));
        button(Component.translatable("gui.back"), width / 2 + 96, bottom, 90, b -> onClose());
    }

    private void updatePreview(BlockPos center) {
        ZoneRenderer.highlighted = target;
        ZoneRenderer.previewCenter = target == null ? center : null;
        ZoneRenderer.previewRadius = newZoneRadius;
    }

    @Override
    public void removed() {
        ZoneRenderer.highlighted = null;
        ZoneRenderer.previewCenter = null;
    }

    private static Component state(boolean hidden) {
        return hidden ? Component.translatable("particlefilter.hidden").withStyle(ChatFormatting.RED)
            : Component.translatable("particlefilter.shown").withStyle(ChatFormatting.GREEN);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("particlefilter.scan.empty"), width / 2, 50, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(font, Component.translatable("particlefilter.everywhere"), width / 2 + 64, TOP - 12, 0xAAAAAA);
            graphics.drawCenteredString(font, Component.translatable("particlefilter.here"), width / 2 + 156, TOP - 12, 0xAAAAAA);
            for (int i = 0; i < rows.size(); i++) {
                Census.Result row = rows.get(i);
                String label = row.count() + "× " + fit(row.displayName(), 190);
                graphics.drawString(font, label, panelLeft(), TOP + i * ROW + 6, row.typed() ? 0x55FFFF : 0xFF55FF);
            }
        }
        int bottom = height - 28;
        graphics.drawString(font, Component.translatable("particlefilter.radius"), panelLeft(), bottom + 6, 0xFFFFFF);
        Component targetText = target != null
            ? Component.translatable("particlefilter.scan.target_zone", target.name)
            : Component.translatable("particlefilter.scan.target_new");
        graphics.drawString(font, targetText, panelLeft(), TOP - 12, 0xAAAAAA);
    }
}
