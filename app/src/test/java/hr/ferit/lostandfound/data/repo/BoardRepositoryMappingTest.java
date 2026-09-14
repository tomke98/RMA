package hr.ferit.lostandfound.data.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import hr.ferit.lostandfound.data.local.BoardReportEntity;
import hr.ferit.lostandfound.data.model.Report;

/**
 * {@code BoardRepository}'s {@code Report}&lt;-&gt;{@code BoardReportEntity}
 * mapping, isolated from Firestore/Room per the spec's I/O matrix "Null
 * createdAt echo" row: a null {@code @ServerTimestamp} must coalesce to
 * {@code System.currentTimeMillis()}, never {@code 0}/crash, and every other
 * field must round-trip unchanged.
 */
public class BoardRepositoryMappingTest {

    @Test
    public void toEntity_withResolvedCreatedAt_usesItsMillis() {
        Report report = report("r1");
        Timestamp createdAt = new Timestamp(1_700_000_000L, 0);
        report.setCreatedAt(createdAt);

        BoardReportEntity entity = BoardRepository.toEntity(report, 42L);

        assertEquals(createdAt.toDate().getTime(), entity.createdAtMillis);
        assertEquals(42L, entity.syncedAt);
    }

    @Test
    public void toEntity_withNullCreatedAt_coalescesToNowNotZero() {
        Report report = report("r1");
        report.setCreatedAt(null);

        long before = System.currentTimeMillis();
        BoardReportEntity entity = BoardRepository.toEntity(report, 42L);
        long after = System.currentTimeMillis();

        assertNotEquals(0L, entity.createdAtMillis);
        assertTrue(entity.createdAtMillis >= before && entity.createdAtMillis <= after);
    }

    @Test
    public void toReport_roundTripsAllFields() {
        Report original = report("r1");
        original.setCreatedAt(new Timestamp(1_700_000_000L, 0));

        BoardReportEntity entity = BoardRepository.toEntity(original, 42L);
        List<Report> roundTripped = BoardRepository.toReports(Collections.singletonList(entity));

        Report result = roundTripped.get(0);
        assertEquals(original.getId(), result.getId());
        assertEquals(original.getType(), result.getType());
        assertEquals(original.getCategory(), result.getCategory());
        assertEquals(original.getDescription(), result.getDescription());
        assertEquals(original.getPhotoUrl(), result.getPhotoUrl());
        assertEquals(original.getLat(), result.getLat(), 0d);
        assertEquals(original.getLng(), result.getLng(), 0d);
        assertEquals(original.getLocationLabel(), result.getLocationLabel());
        assertEquals(original.getReporterId(), result.getReporterId());
        assertEquals(original.getReporterPhone(), result.getReporterPhone());
        assertEquals(original.getCreatedAt().toDate().getTime(), result.getCreatedAt().toDate().getTime());
    }

    private static Report report(String id) {
        Report report = new Report();
        report.setId(id);
        report.setType("lost");
        report.setCategory("OTHER");
        report.setDescription("desc");
        report.setPhotoUrl("https://example.com/photo.jpg");
        report.setLat(45.5d);
        report.setLng(18.6d);
        report.setLocationLabel("label");
        report.setReporterId("u1");
        report.setReporterPhone("0911234567");
        return report;
    }
}
