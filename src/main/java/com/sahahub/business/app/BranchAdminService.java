package com.sahahub.business.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchOpeningHours;
import com.sahahub.business.domain.BranchOpeningHoursRepository;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.BranchSpecialDay;
import com.sahahub.business.domain.BranchSpecialDayRepository;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.business.domain.Surface;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Saha ve şube ayarları (izin: PITCH_MANAGE — sahip ve şube yöneticisi).
 * <p>
 * Ayar değişiklikleri mevcut rezervasyonları değiştirmez (maç süresi, hazırlık ve fiyat rezervasyona
 * kopyalanmıştır). Çalışma saatleri daralırsa yeni saatlerin dışında kalan ileri tarihli rezervasyonlar
 * silinmez; sayıları personele bildirilir. İleri tarihli açık kaydı olan saha rezervasyona kapatılamaz.
 */
@Service
public class BranchAdminService {

	public record PitchCommand(String name, String description, int capacityPlayers, Integer lengthM, Integer widthM,
			boolean indoor, Surface surface, boolean lighting, boolean parking, boolean shower, boolean lockerRoom,
			int slotMinutes, int slotStepMinutes, int bufferMinutes) {
	}

	public record PitchRow(Long id, String name, Surface surface, int capacityPlayers, int slotMinutes,
			int bufferMinutes, BigDecimal basePrice, String currency, boolean active, boolean hasPhoto) {
	}

	public record DayRow(DayOfWeek day, boolean closed, LocalTime open, LocalTime close) {
	}

	public record SpecialDayRow(Long id, LocalDate day, boolean closed, LocalTime open, LocalTime close, String note) {
	}

	public record BranchSettings(Long branchId, String branchName, List<PitchRow> pitches, List<DayRow> week,
			List<SpecialDayRow> specialDays, int holdMinutes, int cancelCutoffHours, int horizonDays) {
	}

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final PitchRepository pitches;
	private final BranchOpeningHoursRepository hours;
	private final BranchSpecialDayRepository specialDays;
	private final AuditService audit;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public BranchAdminService(CatalogService catalog, AccessGuard guard, PitchRepository pitches,
			BranchOpeningHoursRepository hours, BranchSpecialDayRepository specialDays, AuditService audit,
			JdbcTemplate jdbc, Clock clock) {
		this.catalog = catalog;
		this.guard = guard;
		this.pitches = pitches;
		this.hours = hours;
		this.specialDays = specialDays;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ okuma

	@Transactional(readOnly = true)
	public BranchSettings settings(AppUserPrincipal user, Long branchId) {
		BranchContext bc = require(user, branchId);
		Branch b = bc.branch();
		Map<DayOfWeek, DayHours> weekly = new EnumMap<>(DayOfWeek.class);
		hours.findByBranchId(branchId).forEach(h -> weekly.put(h.day(), h.hours()));
		List<DayRow> week = new ArrayList<>();
		for (DayOfWeek d : DayOfWeek.values()) {
			DayHours h = weekly.getOrDefault(d, DayHours.CLOSED);
			week.add(new DayRow(d, h.closed(), h.open(), h.close()));
		}
		LocalDate today = LocalDate.now(clock.withZone(b.zone()));
		List<SpecialDayRow> special = specialDays.findByBranchIdAndDayBetween(branchId, today, today.plusYears(1))
			.stream()
			.sorted(Comparator.comparing(BranchSpecialDay::getDay))
			.map(s -> new SpecialDayRow(s.getId(), s.getDay(), s.hours().closed(), s.hours().open(), s.hours().close(),
					s.getNote()))
			.toList();
		List<PitchRow> rows = pitches.findByBranchIdOrderByName(branchId)
			.stream()
			.map(p -> new PitchRow(p.getId(), p.getName(), p.getSurface(), p.getCapacityPlayers(), p.getSlotMinutes(),
					p.getBufferMinutes(), p.getBaseHourlyPrice(), p.getCurrency(), p.isActive(),
					p.getPhotoPath() != null))
			.toList();
		return new BranchSettings(branchId, b.getName(), rows, week, special, b.getHoldMinutes(),
				b.getCustomerCancelCutoffHours(), b.getBookingHorizonDays());
	}

	@Transactional(readOnly = true)
	public Pitch pitchForEdit(AppUserPrincipal user, Long pitchId) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		require(user, p.getBranchId());
		return p;
	}

	// ------------------------------------------------------------------ saha

	@Transactional
	public Long createPitch(AppUserPrincipal user, Long branchId, PitchCommand cmd, BigDecimal basePrice) {
		BranchContext bc = require(user, branchId);
		validate(cmd);
		if (basePrice == null || basePrice.signum() < 0 || basePrice.compareTo(new BigDecimal("100000")) > 0) {
			throw new BusinessRuleException("Saatlik ücret 0 ile 100.000 ₺ arasında olmalı.");
		}
		Pitch p = new Pitch(branchId, cmd.name().strip(), cmd.capacityPlayers(), cmd.surface(), basePrice,
				Instant.now(clock));
		apply(p, cmd);
		p = pitches.save(p);
		audit.record(user.id(), bc.business().getId(), "PITCH_CREATED", "Pitch", p.getId(), p.getName());
		return p.getId();
	}

	@Transactional
	public void updatePitch(AppUserPrincipal user, Long pitchId, PitchCommand cmd) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = require(user, p.getBranchId());
		validate(cmd);
		String before = p.getName() + " slot=" + p.getSlotMinutes() + "/" + p.getSlotStepMinutes() + " buffer="
				+ p.getBufferMinutes();
		p.rename(cmd.name().strip(), cmd.capacityPlayers(), cmd.surface());
		apply(p, cmd);
		audit.record(user.id(), bc.business().getId(), "PITCH_UPDATED", "Pitch", p.getId(),
				before + " → " + p.getName() + " slot=" + cmd.slotMinutes() + "/" + cmd.slotStepMinutes() + " buffer="
						+ cmd.bufferMinutes());
	}

	/** Sahayı rezervasyona kapatır veya açar. İleri tarihli açık kayıt varsa kapatılamaz. */
	@Transactional
	public void setPitchActive(AppUserPrincipal user, Long pitchId, boolean active) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = require(user, p.getBranchId());
		if (!active) {
			Integer open = jdbc.queryForObject("""
					select (select count(*) from reservation where pitch_id = ? and status in ('HELD', 'CONFIRMED')
					          and ends_at > ?)
					     + (select count(*) from tournament_match where pitch_id = ? and status = 'SCHEDULED'
					          and ends_at > ?)""", Integer.class, pitchId, java.sql.Timestamp.from(Instant.now(clock)),
					pitchId, java.sql.Timestamp.from(Instant.now(clock)));
			if (open != null && open > 0) {
				throw new BusinessRuleException("Sahada " + open
						+ " ileri tarihli rezervasyon veya lig maçı var. Önce bunları taşıyın ya da iptal edin.");
			}
			p.deactivate();
		}
		else {
			p.activate();
		}
		audit.record(user.id(), bc.business().getId(), active ? "PITCH_ACTIVATED" : "PITCH_DEACTIVATED", "Pitch",
				p.getId(), p.getName());
	}

	// ------------------------------------------------------------------ çalışma saatleri ve kurallar

	/**
	 * Haftalık saatleri kaydeder.
	 *
	 * @return yeni saatlerin dışında kalan ileri tarihli açık rezervasyon sayısı (uyarı için)
	 */
	@Transactional
	public int updateWeek(AppUserPrincipal user, Long branchId, Map<DayOfWeek, DayHours> week) {
		BranchContext bc = require(user, branchId);
		for (DayOfWeek d : DayOfWeek.values()) {
			DayHours h = week.getOrDefault(d, DayHours.CLOSED);
			validateHours(h);
			hours.save(new BranchOpeningHours(branchId, d, h));
		}
		hours.flush();
		audit.record(user.id(), bc.business().getId(), "BRANCH_HOURS_UPDATED", "Branch", branchId, week.toString());
		return outsideHours(bc.branch());
	}

	@Transactional
	public int addSpecialDay(AppUserPrincipal user, Long branchId, LocalDate day, DayHours h, String note) {
		BranchContext bc = require(user, branchId);
		LocalDate today = LocalDate.now(clock.withZone(bc.branch().zone()));
		if (day == null || day.isBefore(today) || day.isAfter(today.plusYears(1))) {
			throw new BusinessRuleException("Özel gün bugünden itibaren bir yıl içinde olmalı.");
		}
		validateHours(h);
		String n = note == null || note.isBlank() ? null : note.strip();
		if (n != null && n.length() > 200) {
			throw new BusinessRuleException("Not en fazla 200 karakter.");
		}
		try {
			specialDays.saveAndFlush(new BranchSpecialDay(branchId, day, h, n));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu gün için zaten özel bir kayıt var; önce onu kaldırın.");
		}
		audit.record(user.id(), bc.business().getId(), "BRANCH_SPECIAL_DAY_ADDED", "Branch", branchId,
				day + " " + (h.closed() ? "kapalı" : h.open() + "-" + h.close()));
		return outsideHours(bc.branch());
	}

	@Transactional
	public void removeSpecialDay(AppUserPrincipal user, Long branchId, Long specialDayId) {
		BranchContext bc = require(user, branchId);
		BranchSpecialDay s = specialDays.findById(specialDayId)
			.filter(x -> x.getBranchId().equals(branchId))
			.orElseThrow(() -> new NotFoundException("Özel gün"));
		specialDays.delete(s); // ayardır; geçmiş kayıt ilişkisi yoktur
		audit.record(user.id(), bc.business().getId(), "BRANCH_SPECIAL_DAY_REMOVED", "Branch", branchId,
				s.getDay().toString());
	}

	@Transactional
	public void updatePolicies(AppUserPrincipal user, Long branchId, int holdMinutes, int cancelCutoffHours,
			int horizonDays) {
		BranchContext bc = require(user, branchId);
		if (holdMinutes < 5 || holdMinutes > 30) {
			throw new BusinessRuleException("Geçici tutma süresi 5-30 dakika olmalı.");
		}
		if (cancelCutoffHours < 0 || cancelCutoffHours > 72) {
			throw new BusinessRuleException("İptal süresi 0-72 saat olmalı.");
		}
		if (horizonDays < 1 || horizonDays > 90) {
			throw new BusinessRuleException("Rezervasyon ufku 1-90 gün olmalı.");
		}
		Branch b = bc.branch();
		String before = b.getHoldMinutes() + "/" + b.getCustomerCancelCutoffHours() + "/" + b.getBookingHorizonDays();
		b.updatePolicies(holdMinutes, cancelCutoffHours, horizonDays);
		audit.record(user.id(), bc.business().getId(), "BRANCH_POLICIES_UPDATED", "Branch", branchId,
				before + " → " + holdMinutes + "/" + cancelCutoffHours + "/" + horizonDays);
	}

	// ------------------------------------------------------------------ yardımcılar

	BranchContext require(AppUserPrincipal user, Long branchId) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.PITCH_MANAGE);
		return bc;
	}

	/** İleri tarihli açık rezervasyonlardan şubenin (yeni) saatlerine sığmayanların sayısı. */
	private int outsideHours(Branch branch) {
		Instant now = Instant.now(clock);
		List<TimeRange> open = jdbc.query("""
				select starts_at, ends_at from reservation where branch_id = ? and status in ('HELD', 'CONFIRMED')
				and starts_at > ? order by starts_at limit 2000""",
				(rs, i) -> new TimeRange(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
				branch.getId(), java.sql.Timestamp.from(now));
		if (open.isEmpty()) {
			return 0;
		}
		LocalDate first = open.getFirst().start().atZone(branch.zone()).toLocalDate();
		LocalDate last = open.getLast().start().atZone(branch.zone()).toLocalDate();
		BranchSchedule schedule = catalog.schedule(branch, first.minusDays(1), last);
		return (int) open.stream().filter(r -> !schedule.isOpenDuring(r)).count();
	}

	private static void validate(PitchCommand c) {
		if (c.name() == null || c.name().isBlank() || c.name().strip().length() > 80) {
			throw new BusinessRuleException("Saha adı 1-80 karakter olmalı.");
		}
		if (c.surface() == null) {
			throw new BusinessRuleException("Zemin türünü seçin.");
		}
		if (c.capacityPlayers() < 2 || c.capacityPlayers() > 40) {
			throw new BusinessRuleException("Kapasite 2-40 oyuncu olmalı.");
		}
		if ((c.lengthM() != null && (c.lengthM() < 10 || c.lengthM() > 150))
				|| (c.widthM() != null && (c.widthM() < 5 || c.widthM() > 100))) {
			throw new BusinessRuleException("Ölçü: uzunluk 10-150 m, genişlik 5-100 m.");
		}
		if (c.slotMinutes() < 30 || c.slotMinutes() > 240 || c.slotMinutes() % 15 != 0) {
			throw new BusinessRuleException("Maç süresi 30-240 dakika ve 15'in katı olmalı.");
		}
		if (c.slotStepMinutes() < 15 || c.slotStepMinutes() > 240 || c.slotStepMinutes() % 15 != 0) {
			throw new BusinessRuleException("Başlangıç aralığı 15-240 dakika ve 15'in katı olmalı.");
		}
		if (c.bufferMinutes() < 0 || c.bufferMinutes() > 120 || c.bufferMinutes() % 5 != 0) {
			throw new BusinessRuleException("Hazırlık süresi 0-120 dakika ve 5'in katı olmalı.");
		}
		if (c.description() != null && c.description().length() > 2000) {
			throw new BusinessRuleException("Açıklama en fazla 2000 karakter.");
		}
	}

	private static void apply(Pitch p, PitchCommand c) {
		p.describe(c.description() == null || c.description().isBlank() ? null : c.description().strip(), c.lengthM(),
				c.widthM(), c.indoor());
		p.setAmenities(c.lighting(), c.parking(), c.shower(), c.lockerRoom());
		p.configureSlots(c.slotMinutes(), c.slotStepMinutes(), c.bufferMinutes());
	}

	private static void validateHours(DayHours h) {
		if (h.closed()) {
			return;
		}
		if (h.open().equals(h.close())) {
			throw new BusinessRuleException("Açılış ve kapanış aynı olamaz.");
		}
		long minutes = java.time.Duration.between(h.open(), h.close()).toMinutes();
		if (minutes <= 0) {
			minutes += 24 * 60; // gece yarısını aşan
		}
		if (minutes < 60) {
			throw new BusinessRuleException("Şube günde en az 1 saat açık olmalı.");
		}
		if (h.open().getMinute() % 15 != 0 || h.close().getMinute() % 15 != 0) {
			throw new BusinessRuleException("Saatler 15 dakikanın katı olmalı.");
		}
	}

}
