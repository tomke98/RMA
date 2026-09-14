package hr.ferit.lostandfound.ui.report;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Plain JVM test — {@link PhotoResult} imports no framework type. Mirrors
 * {@link PickedLocationTest}. */
public class PhotoResultTest {

    @Test
    public void getLocalFilePath_returnsConstructorValue() {
        PhotoResult result = new PhotoResult("/data/user/0/hr.ferit.lostandfound/cache/images/abc.jpg");

        assertEquals("/data/user/0/hr.ferit.lostandfound/cache/images/abc.jpg", result.getLocalFilePath());
    }
}
