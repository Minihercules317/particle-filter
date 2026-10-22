package particlefilter.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import particlefilter.Census;
import particlefilter.PfConfig;
import particlefilter.ZoneRenderer;

/** Main menu, opened with /particles, the Open Particle Filter key, or the mod list's Config button. */
public class HubScreen extends PfScreen {

    public HubScreen(Screen parent) {
        super(Component.translatable("particlefilter.hub.title"), parent);
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = 40;
        button(Component.translatable("particlefilter.hub.scan"), x, y, 200, b -> {
            minecraft.setScreen(null);
            Census.startAtCrosshair(true);
        });
        y += ROW;
        button(Component.translatable("particlefilter.hub.results", Census.lastResults().size()), x, y, 200,
            b -> minecraft.setScreen(new ScanScreen(this))).active = !Census.lastResults().isEmpty();
        y += ROW + 8;
        int hiddenCount = PfConfig.hiddenParticles().size() + PfConfig.hiddenBlocks().size()
            + PfConfig.zones().stream().mapToInt(z -> z.particles.size()).sum();
        button(Component.translatable("particlefilter.hub.hidden", hiddenCount), x, y, 200,
            b -> minecraft.setScreen(new HiddenScreen(this)));
        y += ROW;
        button(Component.translatable("particlefilter.hub.zones", PfConfig.zonesHere().size()), x, y, 200,
            b -> minecraft.setScreen(new ZonesScreen(this)));
        y += ROW;
        button(outlinesLabel(), x, y, 200, b -> {
            ZoneRenderer.showOutlines = !ZoneRenderer.showOutlines;
            b.setMessage(outlinesLabel());
        });
        button(Component.translatable("gui.done"), x, height - 28, 200, b -> onClose());
    }

    static Component outlinesLabel() {
        return Component.translatable(ZoneRenderer.showOutlines ? "particlefilter.outlines.on" : "particlefilter.outlines.off");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, Component.translatable("particlefilter.hub.tip"), width / 2, height - 46, 0x999999);
    }
}
