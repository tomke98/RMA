package hr.ferit.lostandfound.ui.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Plain JVM test — {@link PickedLocation} imports no framework type. */
public class PickedLocationTest {

    @Test
    public void getters_returnConstructorValues() {
        PickedLocation location = new PickedLocation(45.55d, 18.69d, "Kod fontane");

        assertEquals(45.55d, location.getLat(), 0d);
        assertEquals(18.69d, location.getLng(), 0d);
        assertEquals("Kod fontane", location.getLabel());
    }

    @Test
    public void label_canBeNull() {
        PickedLocation location = new PickedLocation(45.55d, 18.69d, null);

        assertNull(location.getLabel());
    }
}
