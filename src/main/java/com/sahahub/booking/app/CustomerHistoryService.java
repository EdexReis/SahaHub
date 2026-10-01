package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.business.app.CatalogService;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Personel için müşteri geçmişi: bir rezervasyondan yola çıkarak aynı müşterinin <b>bu şubedeki</b>
 * kayıtları. Kayıtlı müşteri hesabıyla, misafir telefon numarasıyla (yalnızca rakamlar karşılaştırılır)
 * eşleşir. Başka şubelerin ve işletmelerin kayıtları gösterilmez.
 */
@Service
public class CustomerHistoryService {

	public static final int LIMIT = 50;

	public record Row(String code, ZonedDateTime start, String pitchName, ReservationStatus status, BigDecimal total,
			String currency) {
	}

	public record History(String fromCode, String customerName, String phone, boolean registered, int total,
			int completed, int noShows, int cancelled, BigDecimal bookedAmount, List<Row> rows) {
	}

	private final ReservationRepository reservations;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final JdbcTemplate jdbc;
	private final ReservationViewFactory views;

	public CustomerHistoryService(ReservationRepository reservations, CatalogService catalog, AccessGuard guard,
			JdbcTemplate jdbc, ReservationViewFactory views) {
		this.reservations = reservations;
		this.catalog = catalog;
		this.guard = guard;
		this.jdbc = jdbc;
		this.views = views;
	}

	@Transactional(readOnly = true)
	public History forReservation(AppUserPrincipal user, String code) {
		Reservation anchor = reservations.findByCode(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(user, anchor.getBusinessId(), anchor.getBranchId(), Permission.CALENDAR_VIEW);
		ZoneId zone = catalog.branchContext(anchor.getBranchId()).branch().zone();
		ReservationView v = views.build(anchor);
		String sql = """
				select r.code, r.starts_at, p.name, r.status, r.total_amount, r.currency from reservation r
				join pitch p on p.id = r.pitch_id where r.branch_id = ? and %s
				order by r.starts_at desc limit ?""";
		List<Row> rows;
		boolean registered = anchor.getCustomerId() != null;
		if (registered) {
			rows = jdbc.query(sql.formatted("r.customer_id = ?"), (rs, i) -> row(rs, zone), anchor.getBranchId(),
					anchor.getCustomerId(), LIMIT);
		}
		else if (anchor.getGuestPhone() != null && digits(anchor.getGuestPhone()).length() >= 7) {
			rows = jdbc.query(
					sql.formatted("r.customer_id is null and regexp_replace(r.guest_phone, '[^0-9]', '', 'g') = ?"),
					(rs, i) -> row(rs, zone), anchor.getBranchId(), digits(anchor.getGuestPhone()), LIMIT);
		}
		else {
			rows = List.of(row(anchor, v));
		}
		int completed = 0;
		int noShows = 0;
		int cancelled = 0;
		BigDecimal booked = BigDecimal.ZERO;
		for (Row r : rows) {
			switch (r.status()) {
				case COMPLETED -> completed++;
				case NO_SHOW -> noShows++;
				case CANCELLED -> cancelled++;
				default -> {
				}
			}
			if (r.status() == ReservationStatus.CONFIRMED || r.status() == ReservationStatus.COMPLETED
					|| r.status() == ReservationStatus.NO_SHOW) {
				booked = booked.add(r.total());
			}
		}
		return new History(code, v.customerName(), v.contactPhone(), registered, rows.size(), completed, noShows,
				cancelled, booked, rows);
	}

	private static Row row(java.sql.ResultSet rs, ZoneId zone) throws java.sql.SQLException {
		return new Row(rs.getString(1), rs.getTimestamp(2).toInstant().atZone(zone), rs.getString(3),
				ReservationStatus.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getString(6));
	}

	private static Row row(Reservation r, ReservationView v) {
		return new Row(r.getCode(), v.start(), v.pitchName(), r.getStatus(), r.getTotalAmount(), r.getCurrency());
	}

	static String digits(String phone) {
		return phone.replaceAll("[^0-9]", "");
	}

}
