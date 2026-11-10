package particlefilter.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterModeTest {

    @Test
    void cycleVisitsEveryModeAndWrapsAround() {
        assertEquals(FilterMode.EVERYWHERE, FilterMode.ALL.next());
        assertEquals(FilterMode.ZONES, FilterMode.EVERYWHERE.next());
        assertEquals(FilterMode.NONE, FilterMode.ZONES.next());
        assertEquals(FilterMode.ALL, FilterMode.NONE.next());
    }

    @Test
    void eachModeSwitchesTheRightRules() {
        assertTrue(FilterMode.ALL.global && FilterMode.ALL.zones);
        assertTrue(FilterMode.EVERYWHERE.global && !FilterMode.EVERYWHERE.zones);
        assertTrue(!FilterMode.ZONES.global && FilterMode.ZONES.zones);
        assertTrue(!FilterMode.NONE.global && !FilterMode.NONE.zones);
    }

    @Test
    void everyModeHasANameAndTooltipInEnglish() throws Exception {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/particlefilter/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is missing");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (FilterMode mode : FilterMode.values()) {
            assertTrue(lang.has(mode.key()), "no translation for " + mode.key());
            assertTrue(lang.has(mode.key() + ".tooltip"), "no translation for " + mode.key() + ".tooltip");
        }
    }
}
