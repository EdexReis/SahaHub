package com.sahahub.reporting.app;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.reporting.domain.ReportCalculator;
import com.sahahub.reporting.domain.ReportCalculator.Booking;
import com.sahahub.reporting.domain.ReportCalculator.CollectionRow;
import com.sahahub.reporting.domain.ReportCalculator.ExpenseItem;
import com.sahahub.reporting.domain.ReportCalculator.Grouping;
import com.sahahub.reporting.domain.ReportCalculator.Interval;
import com.sahahub.reporting.domain.ReportCalculator.Movement;
import com.sahahub.reporting.domain.ReportCalculator.Period;
import com.sahahub.reporting.domain.ReportCalculator.PitchUsage;

/**
 * Şube ve işletme raporları. Veriler salt okunur SQL ile tek seferde yüklenir, hesaplar
 * {@link ReportCalculator}'da yapılır. Yetki: şube raporu REPORT_VIEW (sahip, şube yöneticisi);
 * şube karşılaştırması işletme geneli olduğu için yalnızca işletme sahibi.
 */
@Service
public class ReportService {

	public record BranchReport(Long branchId, String branchName, String currency, Period period, Grouping grouping,
			List<CollectionRow> collections, CollectionRow collectionTotal, ReportCalculator.Receivables receivables,
			List<PitchUsage> usage, PitchUsage usageTotal, ReportCalculator.Heatmap heatmap,
			ReportCalculator.Rates rates, ReportCalculator.Repeat repeat, ReportCalculator.ExpenseSummary expenses) {

		/** Net tahsilat − giderler. Kâr değildir. */
		public BigDecimal cashDifference() {
			return collectionTotal.net().subtract(expenses.total());
		}

	}

	public record BranchComparison(Long branchId, String branchName, BigDecimal booked, BigDecimal netCollected,
			BigDecimal expenses, BigDecimal occupancy, BigDecimal cancelRate, int reservations) {
	}

	private final JdbcTemplate jdbc;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final Clock clock;

	public ReportService(JdbcTemplate jdbc, CatalogService catalog, AccessGuard guard, Clock clock) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.guard = guard;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public BranchReport branchReport(AppUserPrincipal user, Long branchId, Period period, Grouping grouping) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.REPORT_VIEW);
		return build(bc.branch(), period, grouping);
	}

	/** İşletmenin şubelerini aynı metriklerle yan yana koyar (yalnızca işletme sahibi). */
	@Transactional(readOnly = true)
	public List<BranchComparison> compareBranches(AppUserPrincipal user, Long businessId, Period period) {
		guard.requireBusiness(user, businessId, Permission.REPORT_VIEW);
		List<BranchComparison> rows = new ArrayList<>();
		for (Long id : jdbc.queryForList("select id from branch where business_id = ? and archived = false order by id",
				Long.class, businessId)) {
			BranchReport r = build(catalog.branchContext(id).branch(), period, Grouping.MONTH);
			rows.add(new BranchComparison(id, r.branchName(), r.receivables().booked(), r.collectionTotal().net(),
					r.expenses().total(), r.usageTotal().occupancy(), r.rates().cancelRate(),
					r.receivables().count()));
		}
		return rows;
	}

	// ------------------------------------------------------------------ yükleme

	private BranchReport build(Branch branch, Period period, Grouping grouping) {
		ZoneId zone = branch.zone();
		BranchSchedule schedule = catalog.schedule(branch, period.from().minusDays(1), period.to());
		// İş günü penceresi gece yarısını aşabilir: aralığı bir gün geniş yükle, sonra iş gününe göre süz
		Instant from = period.from().atStartOfDay(zone).toInstant();
		Instant to = period.to().plusDays(2).atStartOfDay(zone).toInstant();
		Timestamp f = Timestamp.from(from);
		Timestamp t = Timestamp.from(to);

		Map<Long, String> pitches = new LinkedHashMap<>();
		jdbc.query("select id, name from pitch where branch_id = ? order by id",
				rs -> {
					pitches.put(rs.getLong(1), rs.getString(2));
				}, branch.getId());

		List<Booking> bookings = jdbc.query("""
				select r.id, r.pitch_id, r.customer_id, u.full_name, r.status, r.starts_at, r.ends_at, r.buffer_minutes,
				       r.total_amount, r.confirmed_at, r.cancelled_by
				from reservation r left join app_user u on u.id = r.customer_id
				where r.branch_id = ? and r.starts_at >= ? and r.starts_at < ?""", (rs, i) -> {
			Instant start = rs.getTimestamp(6).toInstant();
			Instant end = rs.getTimestamp(7).toInstant();
			LocalDate day = schedule.businessDayOf(new com.sahahub.shared.domain.TimeRange(start, end))
				.orElse(start.atZone(zone).toLocalDate());
			Timestamp confirmed = rs.getTimestamp(10);
			return new Booking(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5),
					start, end, rs.getInt(8), rs.getBigDecimal(9), confirmed == null ? null : confirmed.toInstant(),
					(Long) rs.getObject(11), day);
		}, branch.getId(), Timestamp.from(from.minus(java.time.Duration.ofDays(1))), t);
		bookings = bookings.stream().filter(b -> period.contains(b.businessDay())).toList();

		List<Movement> movements = jdbc.query("""
				select reservation_id, kind, method, amount, completed_at from payment
				where branch_id = ? and status = 'SUCCEEDED' and completed_at >= ? and completed_at < ?""",
				(rs, i) -> new Movement(rs.getLong(1), rs.getString(2), ReportCalculator.Method.valueOf(rs.getString(3)),
						rs.getBigDecimal(4), rs.getTimestamp(5).toInstant()),
				branch.getId(), f, Timestamp.from(period.to().plusDays(1).atStartOfDay(zone).toInstant()));

		Map<Long, BigDecimal> netPaid = new HashMap<>();
		if (!bookings.isEmpty()) {
			Long[] ids = bookings.stream().map(Booking::id).toArray(Long[]::new);
			jdbc.query("""
					select reservation_id, sum(case when kind = 'CHARGE' then amount else -amount end)
					from payment where status = 'SUCCEEDED' and reservation_id = any(?) group by reservation_id""",
					ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
					rs -> {
						netPaid.put(rs.getLong(1), rs.getBigDecimal(2));
					});
		}

		List<Interval> blocks = jdbc.query("""
				select b.pitch_id, b.starts_at, b.ends_at from pitch_block b join pitch p on p.id = b.pitch_id
				where p.branch_id = ? and b.cancelled = false and b.starts_at < ? and b.ends_at > ?""",
				(rs, i) -> new Interval(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
				branch.getId(), t, Timestamp.from(from.minus(java.time.Duration.ofDays(1))));
		List<Interval> leagueMatches = jdbc.query("""
				select m.pitch_id, m.starts_at, m.ends_at from tournament_match m join pitch p on p.id = m.pitch_id
				where p.branch_id = ? and m.status <> 'UNSCHEDULED' and m.starts_at < ? and m.ends_at > ?""",
				(rs, i) -> new Interval(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
				branch.getId(), t, Timestamp.from(from.minus(java.time.Duration.ofDays(1))));
		List<ExpenseItem> expenseItems = jdbc.query("""
				select category, amount, created_at from expense where branch_id = ? and created_at >= ? and created_at < ?""",
				(rs, i) -> new ExpenseItem(com.sahahub.payment.domain.Expense.Category.valueOf(rs.getString(1)).label(),
						rs.getBigDecimal(2), rs.getTimestamp(3).toInstant()),
				branch.getId(), f, Timestamp.from(period.to().plusDays(1).atStartOfDay(zone).toInstant()));

		List<CollectionRow> collections = ReportCalculator.collections(movements, period, grouping, zone);
		List<PitchUsage> usage = ReportCalculator.usage(pitches, schedule, period, bookings, blocks, leagueMatches);
		String currency = jdbc.queryForList("select currency from pitch where branch_id = ? limit 1", String.class,
				branch.getId()).stream().findFirst().orElse("TRY");
		return new BranchReport(branch.getId(), branch.getName(), currency, period, grouping, collections,
				ReportCalculator.total(collections), ReportCalculator.receivables(bookings, netPaid), usage,
				ReportCalculator.totalUsage(usage), ReportCalculator.peakHours(bookings, period, zone),
				ReportCalculator.rates(bookings, period, Instant.now(clock)),
				ReportCalculator.repeatCustomers(bookings, period, 10),
				ReportCalculator.expenses(expenseItems, period, zone));
	}

}
