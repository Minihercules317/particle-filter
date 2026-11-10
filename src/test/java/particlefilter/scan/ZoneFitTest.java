package particlefilter.scan;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneFitTest {

    @Test
    void oneSpawnPointFitsItsBlock() {
        AABB point = new AABB(5.5, 64.2, -3.7, 5.5, 64.2, -3.7);

        ZoneFit.Box box = ZoneFit.fit(null, List.of(point));

        assertEquals(new ZoneFit.Box(5, 64, -4, 1, 1, 1, false), box);
    }
}
