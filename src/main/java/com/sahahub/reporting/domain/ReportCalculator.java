package com.sahahub.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.shared.domain.TimeRange;

/**
 * Rapor metriklerinin hesapları. Saf fonksiyonlardır (veritabanı ve Spring yok); her metriğin tanımı
 * burada ve docs/RAPORLAR.md'de aynıdır. ReportCalculatorTest tanımları sabitler.
 * <p>
 * Üç para kavramı birbirine karıştırılmaz:
 * <ul>
 * <li><b>Rezervasyon bedeli</b>: onaylı maçların fiyatı (alacak doğurur, para değildir).</li>
 * <li><b>Tahsilat</b>: kasaya/hesaba gerçekten giren para (başarılı ödeme hareketleri), ödeme tarihine göre.</li>
 * <li><b>Nakit farkı</b>: net tahsilat − giderler. Kâr değildir (vergi, maaş, amortisman vb. yok).</li>
 * </ul>
 */
public final class ReportCalculator {

	public static final int MAX_DAYS = 366;

	/** Rezervasyon bedeli doğuran (alacaklı) durumlar. */
	public static final Set<String> BOOKED = Set.of("CONFIRMED", "COMPLETED", "NO_SHOW");

	private ReportCalculator() {
	}

	// ------------------------------------------------------------------ girdiler

	/** Kapalı tarih aralığı [from, to], şubenin takviminde. */
	public record Period(LocalDate from, LocalDate to) {

		public Period {
			if (from == null || to == null || to.isBefore(from)) {
				throw new IllegalArgumentException("Bitiş tarihi başlangıçtan önce olamaz.");
			}
			if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
				throw new IllegalArgumentException("Rapor aralığı en fazla " + MAX_DAYS + " gün olabilir.");
			}
		}

		public boolean contains(LocalDate d) {
			return !d.isBefore(from) && !d.isAfter(to);
		}

		public List<LocalDate> days() {
			return from.datesUntil(to.plusDays(1)).toList();
		}

		public long dayCount() {
			return ChronoUnit.DAYS.between(from, to) + 1;
		}

	}

	public enum Grouping {

		DAY("Günlük"), WEEK("Haftalık"), MONTH("Aylık");

		private final String label;

		Grouping(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		LocalDate bucketStart(LocalDate d) {
			return switch (this) {
				case DAY -> d;
				case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				case MONTH -> d.withDayOfMonth(1);
			};
		}

		LocalDate bucketEnd(LocalDate start) {
			return switch (this) {
				case DAY -> start;
				case WEEK -> start.plusDays(6);
				case MONTH -> start.plusMonths(1).minusDays(1);
			};
		}

	}

	/**
	 * Rapora giren rezervasyon. businessDay: maçın ait olduğu iş günü (gece yarısını aşan maçlar önceki güne).
	 */
	public record Booking(long id, long pitchId, Long customerId, String customerName, String status, Instant start,
			Instant end, int bufferMinutes, BigDecimal total, Instant confirmedAt, Long cancelledBy,
			LocalDate businessDay) {

		public boolean booked() {
			return BOOKED.contains(status);
		}

		public long playMinutes() {
			return Duration.between(start, end).toMinutes();
		}

	}

	public enum Method {
		CASH, MANUAL_POS, BANK_TRANSFER, ONLINE_SIM
	}

	/** Başarılı ödeme hareketi. kind: CHARGE, REFUND, REVERSAL. */
	public record Movement(long reservationId, String kind, Method method, BigDecimal amount, Instant completedAt) {

		/** Tahsilata etkisi: tahsilat +, iade ve ters kayıt −. */
		public BigDecimal signed() {
			return "CHARGE".equals(kind) ? amount : amount.negate();
		}

	}

	public record Interval(long pitchId, Instant start, Instant end) {

		TimeRange range() {
			return new TimeRange(start, end);
		}

	}

	public record ExpenseItem(String category, BigDecimal amount, Instant at) {
	}

	// ------------------------------------------------------------------ 1. tahsilat

	public record CollectionRow(LocalDate start, LocalDate end, BigDecimal cash, BigDecimal pos, BigDecimal transfer,
			BigDecimal online, BigDecimal refunds, BigDecimal reversals) {

		/** Brüt tahsilat: tüm yöntemlerle başarılı tahsilatlar. */
		public BigDecimal charges() {
			return cash.add(pos).add(transfer).add(online);
		}

		/** Net tahsilat = brüt tahsilat − iadeler − ters kayıtlar. */
		public BigDecimal net() {
			return charges().subtract(refunds).subtract(reversals);
		}

	}

	/**
	 * Ödeme tarihine (tamamlanma anının şube saatindeki günü) göre gruplanmış tahsilat. Aralıktaki her dönem,
	 * hareket olmasa da satır olarak döner (boş günler sıfır görünür).
	 */
	public static List<CollectionRow> collections(List<Movement> movements, Period period, Grouping grouping,
			ZoneId zone) {
		Map<LocalDate, BigDecimal[]> buckets = new LinkedHashMap<>();
		for (LocalDate d = grouping.bucketStart(period.from()); !d.isAfter(period.to());
				d = grouping.bucketEnd(d).plusDays(1)) {
			buckets.put(d, zeros(6));
		}
		for (Movement m : movements) {
			LocalDate day = m.completedAt().atZone(zone).toLocalDate();
			if (!period.contains(day)) {
				continue;
			}
			BigDecimal[] b = buckets.get(grouping.bucketStart(day));
			int slot = switch (m.kind()) {
				case "REFUND" -> 4;
				case "REVERSAL" -> 5;
				default -> switch (m.method()) {
					case CASH -> 0;
					case MANUAL_POS -> 1;
					case BANK_TRANSFER -> 2;
					case ONLINE_SIM -> 3;
				};
			};
			b[slot] = b[slot].add(m.amount());
		}
		List<CollectionRow> rows = new ArrayList<>();
		buckets.forEach((start, b) -> {
			LocalDate end = grouping.bucketEnd(start);
			// İlk ve son dönem aralık sınırında kırpılır (ör. ayın 10'undan başlayan rapor)
			LocalDate s = start.isBefore(period.from()) ? period.from() : start;
			LocalDate e = end.isAfter(period.to()) ? period.to() : end;
			rows.add(new CollectionRow(s, e, b[0], b[1], b[2], b[3], b[4], b[5]));
		});
		return rows;
	}

	public static CollectionRow total(List<CollectionRow> rows) {
		BigDecimal[] t = zeros(6);
		for (CollectionRow r : rows) {
			t[0] = t[0].add(r.cash());
			t[1] = t[1].add(r.pos());
			t[2] = t[2].add(r.transfer());
			t[3] = t[3].add(r.online());
			t[4] = t[4].add(r.refunds());
			t[5] = t[5].add(r.reversals());
		}
		LocalDate s = rows.isEmpty() ? null : rows.getFirst().start();
		LocalDate e = rows.isEmpty() ? null : rows.getLast().end();
		return new CollectionRow(s, e, t[0], t[1], t[2], t[3], t[4], t[5]);
	}

	// ------------------------------------------------------------------ 2. rezervasyon bedeli ve alacak

	/**
	 * @param booked onaylı maçların bedeli (iş günü aralıkta)
	 * @param collected bu maçlar için alınmış net tahsilat (ödeme tarihi ne olursa olsun)
	 * @param outstanding kalan alacak: maç başına max(0, bedel − net tahsilat) toplamı
	 * @param refundDue iade edilmesi gereken: maç başına fazla ödeme + iptal/süresi dolmuş kayıtlarda kalan para
	 */
	public record Receivables(int count, BigDecimal booked, BigDecimal collected, BigDecimal outstanding,
			BigDecimal refundDue) {
	}

	public static Receivables receivables(List<Booking> bookings, Map<Long, BigDecimal> netPaid) {
		int count = 0;
		BigDecimal booked = BigDecimal.ZERO;
		BigDecimal collected = BigDecimal.ZERO;
		BigDecimal outstanding = BigDecimal.ZERO;
		BigDecimal refundDue = BigDecimal.ZERO;
		for (Booking b : bookings) {
			BigDecimal net = netPaid.getOrDefault(b.id(), BigDecimal.ZERO);
			if (b.booked()) {
				count++;
				booked = booked.add(b.total());
				collected = collected.add(net);
				BigDecimal diff = b.total().subtract(net);
				if (diff.signum() > 0) {
					outstanding = outstanding.add(diff);
				}
				else {
					refundDue = refundDue.add(diff.negate());
				}
			}
			else if (net.signum() > 0) {
				refundDue = refundDue.add(net); // iptal edilmiş/süresi dolmuş kayıtta kalan para
			}
		}
		return new Receivables(count, booked, collected, outstanding, refundDue);
	}

	// ------------------------------------------------------------------ 3. doluluk

	/**
	 * Saha kullanımı (dakika).
	 * <ul>
	 * <li>open: aralıktaki iş günlerinin çalışma penceresi toplamı (kapalı günler 0).</li>
	 * <li>blocked: bakım/etkinlik kapatmalarının pencere içindeki kısmı.</li>
	 * <li>sellable = open − blocked (paydadır).</li>
	 * <li>reserved: onaylı maçların oyun süresi (hazırlık hariç). match: lig maçları.</li>
	 * <li>buffer: maç sonrası hazırlık süreleri. Paydan düşülmez, paya eklenmez; ayrıca gösterilir.</li>
	 * </ul>
	 * occupancy = (reserved + match) / sellable. busy = (reserved + match + buffer) / sellable.
	 */
	public record PitchUsage(long pitchId, String name, long open, long blocked, long reserved, long match,
			long buffer, int reservations, BigDecimal booked) {

		public long sellable() {
			return Math.max(0, open - blocked);
		}

		public BigDecimal occupancy() {
			return percent(reserved + match, sellable());
		}

		public BigDecimal busy() {
			return percent(reserved + match + buffer, sellable());
		}

	}

	public static List<PitchUsage> usage(Map<Long, String> pitches, BranchSchedule schedule, Period period,
			List<Booking> bookings, List<Interval> blocks, List<Interval> matches) {
		List<TimeRange> windows = period.days()
			.stream()
			.map(schedule::windowFor)
			.flatMap(Optional::stream)
			.toList();
		long open = windows.stream().mapToLong(w -> minutes(w)).sum();
		List<PitchUsage> rows = new ArrayList<>();
		pitches.forEach((pitchId, name) -> {
			long blocked = overlapMinutes(blocks, pitchId, windows);
			long match = overlapMinutes(matches, pitchId, windows);
			long reserved = 0;
			long buffer = 0;
			int count = 0;
			BigDecimal booked = BigDecimal.ZERO;
			for (Booking b : bookings) {
				if (b.pitchId() == pitchId && b.booked() && period.contains(b.businessDay())) {
					reserved += b.playMinutes();
					buffer += b.bufferMinutes();
					count++;
					booked = booked.add(b.total());
				}
			}
			rows.add(new PitchUsage(pitchId, name, open, blocked, reserved, match, buffer, count, booked));
		});
		return rows;
	}

	public static PitchUsage totalUsage(List<PitchUsage> rows) {
		long open = 0;
		long blocked = 0;
		long reserved = 0;
		long match = 0;
		long buffer = 0;
		int count = 0;
		BigDecimal booked = BigDecimal.ZERO;
		for (PitchUsage u : rows) {
			open += u.open();
			blocked += u.blocked();
			reserved += u.reserved();
			match += u.match();
			buffer += u.buffer();
			count += u.reservations();
			booked = booked.add(u.booked());
		}
		return new PitchUsage(0, "Toplam", open, blocked, reserved, match, buffer, count, booked);
	}

	private static long overlapMinutes(List<Interval> list, long pitchId, List<TimeRange> windows) {
		long sum = 0;
		for (Interval i : list) {
			if (i.pitchId() != pitchId) {
				continue;
			}
			for (TimeRange w : windows) {
				Instant s = i.start().isAfter(w.start()) ? i.start() : w.start();
				Instant e = i.end().isBefore(w.end()) ? i.end() : w.end();
				if (e.isAfter(s)) {
					sum += Duration.between(s, e).toMinutes();
				}
			}
		}
		return sum;
	}

	// ------------------------------------------------------------------ 4. yoğun saatler

	/**
	 * Haftanın günü × başlangıç saati başına onaylı maç sayısı. Saatler iş günü sırasıyla dizilir
	 * (06:00 … 23:00, sonra gece 00:00 … 05:00) ve yalnızca en az bir maç olan saat aralığı gösterilir.
	 */
	public record Heatmap(List<Integer> hours, Map<DayOfWeek, int[]> counts, int max) {

		public int count(DayOfWeek day, int hourIndex) {
			return counts.get(day)[hourIndex];
		}

		/** 0-4 arası yoğunluk kademesi (ekranda gölge + sayı birlikte gösterilir). */
		public int level(DayOfWeek day, int hourIndex) {
			int c = count(day, hourIndex);
			return c == 0 || max == 0 ? 0 : Math.max(1, (int) Math.ceil(4.0 * c / max));
		}

	}

	public static Heatmap peakHours(List<Booking> bookings, Period period, ZoneId zone) {
		int[][] raw = new int[7][24];
		for (Booking b : bookings) {
			if (b.booked() && period.contains(b.businessDay())) {
				var local = b.start().atZone(zone);
				// Gece yarısından sonraki maç, iş gününün satırında sayılır
				raw[b.businessDay().getDayOfWeek().getValue() - 1][local.getHour()]++;
			}
		}
		List<Integer> order = new ArrayList<>();
		for (int h = 6; h < 24; h++) {
			order.add(h);
		}
		for (int h = 0; h < 6; h++) {
			order.add(h);
		}
		int first = -1;
		int last = -1;
		for (int i = 0; i < order.size(); i++) {
			int h = order.get(i);
			boolean any = false;
			for (int d = 0; d < 7; d++) {
				any |= raw[d][h] > 0;
			}
			if (any) {
				first = first < 0 ? i : first;
				last = i;
			}
		}
		List<Integer> hours = first < 0 ? List.of() : order.subList(first, last + 1);
		Map<DayOfWeek, int[]> counts = new EnumMap<>(DayOfWeek.class);
		int max = 0;
		for (DayOfWeek d : DayOfWeek.values()) {
			int[] row = new int[hours.size()];
			for (int i = 0; i < hours.size(); i++) {
				row[i] = raw[d.getValue() - 1][hours.get(i)];
				max = Math.max(max, row[i]);
			}
			counts.put(d, row);
		}
		return new Heatmap(List.copyOf(hours), counts, max);
	}

	// ------------------------------------------------------------------ 5. iptal ve gelmeme

	/**
	 * <ul>
	 * <li>confirmed: aralıkta onaylanmış (sonradan iptal edilenler dahil) maç sayısı — iptal oranının paydası.</li>
	 * <li>cancelled: onaylandıktan sonra iptal edilenler (müşteri / şube ayrımıyla).</li>
	 * <li>droppedHolds: onaylanmadan bırakılan veya süresi dolan geçici tutmalar (iptal oranına girmez).</li>
	 * <li>noShowRate = gelmedi / (tamamlandı + gelmedi). Durumu işaretlenmemiş geçmiş maçlar ayrıca sayılır.</li>
	 * </ul>
	 */
	public record Rates(int confirmed, int cancelledByCustomer, int cancelledByStaff, int droppedHolds, int completed,
			int noShows, int unmarkedPast) {

		public int cancelled() {
			return cancelledByCustomer + cancelledByStaff;
		}

		public BigDecimal cancelRate() {
			return percent(cancelled(), confirmed);
		}

		public BigDecimal noShowRate() {
			return percent(noShows, completed + noShows);
		}

	}

	public static Rates rates(List<Booking> bookings, Period period, Instant now) {
		int confirmed = 0;
		int byCustomer = 0;
		int byStaff = 0;
		int dropped = 0;
		int completed = 0;
		int noShow = 0;
		int unmarked = 0;
		for (Booking b : bookings) {
			if (!period.contains(b.businessDay())) {
				continue;
			}
			if (b.confirmedAt() != null) {
				confirmed++;
			}
			switch (b.status()) {
				case "CANCELLED" -> {
					if (b.confirmedAt() == null) {
						dropped++;
					}
					else if (b.cancelledBy() != null && b.cancelledBy().equals(b.customerId())) {
						byCustomer++;
					}
					else {
						byStaff++;
					}
				}
				case "EXPIRED" -> dropped++;
				case "COMPLETED" -> completed++;
				case "NO_SHOW" -> noShow++;
				case "CONFIRMED" -> {
					if (!b.end().isAfter(now)) {
						unmarked++;
					}
				}
				default -> {
					// HELD: henüz sonuçlanmadı
				}
			}
		}
		return new Rates(confirmed, byCustomer, byStaff, dropped, completed, noShow, unmarked);
	}

	// ------------------------------------------------------------------ 6. tekrar gelen müşteriler

	public record CustomerVisits(long customerId, String name, int visits, LocalDate last) {
	}

	/**
	 * Kayıtlı müşteriler: aralıkta en az bir onaylı (iptal edilmemiş) maçı olanlar. Tekrar gelen: en az iki.
	 * Misafir (hesapsız) kayıtlar kişi olarak eşleştirilemediği için sayılmaz.
	 */
	public record Repeat(int distinct, int repeat, int guestBookings, List<CustomerVisits> top) {

		public BigDecimal repeatRate() {
			return percent(repeat, distinct);
		}

	}

	public static Repeat repeatCustomers(List<Booking> bookings, Period period, int topN) {
		Map<Long, CustomerVisits> by = new HashMap<>();
		int guests = 0;
		for (Booking b : bookings) {
			if (!b.booked() || !period.contains(b.businessDay())) {
				continue;
			}
			if (b.customerId() == null) {
				guests++;
				continue;
			}
			by.merge(b.customerId(), new CustomerVisits(b.customerId(), b.customerName(), 1, b.businessDay()),
					(a, c) -> new CustomerVisits(a.customerId(), a.name(), a.visits() + 1,
							a.last().isAfter(c.last()) ? a.last() : c.last()));
		}
		List<CustomerVisits> sorted = by.values()
			.stream()
			.sorted(Comparator.comparingInt(CustomerVisits::visits).reversed()
				.thenComparing(CustomerVisits::last, Comparator.reverseOrder())
				.thenComparing(CustomerVisits::customerId))
			.toList();
		int repeat = (int) sorted.stream().filter(v -> v.visits() >= 2).count();
		return new Repeat(sorted.size(), repeat, guests,
				sorted.stream().filter(v -> v.visits() >= 2).limit(topN).toList());
	}

	// ------------------------------------------------------------------ 7. gider ve nakit farkı

	public record ExpenseSummary(Map<String, BigDecimal> byCategory, BigDecimal total) {
	}

	/** Giderler kayıt tarihine göre; ters kayıtlar (eksi tutar) düşülür. */
	public static ExpenseSummary expenses(List<ExpenseItem> items, Period period, ZoneId zone) {
		Map<String, BigDecimal> by = new LinkedHashMap<>();
		BigDecimal total = BigDecimal.ZERO;
		for (ExpenseItem e : items) {
			if (!period.contains(e.at().atZone(zone).toLocalDate())) {
				continue;
			}
			by.merge(e.category(), e.amount(), BigDecimal::add);
			total = total.add(e.amount());
		}
		return new ExpenseSummary(by, total);
	}

	// ------------------------------------------------------------------ yardımcılar

	/** Yüzde, bir ondalık; payda 0 ise null ("—" gösterilir). */
	public static BigDecimal percent(long part, long whole) {
		if (whole <= 0) {
			return null;
		}
		return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
	}

	private static long minutes(TimeRange r) {
		return Duration.between(r.start(), r.end()).toMinutes();
	}

	private static BigDecimal[] zeros(int n) {
		BigDecimal[] a = new BigDecimal[n];
		java.util.Arrays.fill(a, BigDecimal.ZERO);
		return a;
	}

	/** Liste → kimliğe göre harita (rapor servisinde kullanılır). */
	public static <T> Map<Long, T> index(List<T> list, Function<T, Long> key) {
		Map<Long, T> m = new LinkedHashMap<>();
		list.forEach(x -> m.put(key.apply(x), x));
		return m;
	}

}
