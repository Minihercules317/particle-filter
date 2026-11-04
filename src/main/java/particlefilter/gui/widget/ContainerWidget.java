package particlefilter.gui.widget;

import net.minecraft.network.chat.Component;
//? if >1.20.1 {
import net.minecraft.client.gui.components.AbstractContainerWidget;
//?} else {
/*import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
*///?}

/**
 * A widget that holds other widgets. Vanilla's AbstractContainerWidget, which 1.20.1 doesn't have yet; there this
 * class does the same job.
 */
//? if >1.20.1 {
public abstract class ContainerWidget extends AbstractContainerWidget {

    protected ContainerWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }
}
//?} else {
/*public abstract class ContainerWidget extends AbstractWidget implements ContainerEventHandler {

    private GuiEventListener focused;
    private boolean dragging;

    protected ContainerWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Override
    public final boolean isDragging() {
        return dragging;
    }

    @Override
    public final void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    @Override
    public GuiEventListener getFocused() {
        return focused;
    }

    @Override
    public void setFocused(GuiEventListener child) {
        if (focused != null) focused.setFocused(false);
        if (child != null) child.setFocused(true);
        focused = child;
    }

    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent event) {
        return ContainerEventHandler.super.nextFocusPath(event);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return ContainerEventHandler.super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return ContainerEventHandler.super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return ContainerEventHandler.super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean isFocused() {
        return ContainerEventHandler.super.isFocused();
    }

    @Override
    public void setFocused(boolean focused) {
        ContainerEventHandler.super.setFocused(focused);
    }
}
*///?}
