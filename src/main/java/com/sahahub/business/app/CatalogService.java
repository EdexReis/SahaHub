package com.sahahub.business.app;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchOpeningHours;
import com.sahahub.business.domain.BranchOpeningHoursRepository;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.BranchSpecialDay;
import com.sahahub.business.domain.BranchSpecialDayRepository;
import com.sahahub.business.domain.Business;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.pricing.domain.PriceRule;
import com.sahahub.pricing.domain.PriceRuleRepository;
import com.sahahub.shared.domain.NotFoundException;

import jakarta.persistence.EntityManager;

/** İşletme/şube/saha bilgilerini okuyan servis. Yazma işlemleri ilgili modüllerdedir. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

	/** Saha + bağlı olduğu şube ve işletme. */
	public record PitchContext(Pitch pitch, Branch branch, Business business) {

		/** Müşteri bu sahayı görebilir ve rezerve edebilir mi? */
		public boolean publiclyBookable() {
			return pitch.isActive() && !branch.isArchived() && business.isActive();
		}

	}

	public record BranchContext(Branch branch, Business business) {
	}

	/** Saha keşfi kartı. */
	public record PitchCard(Long id, String name, String branchName, String businessName, String district,
			String city, String surfaceLabel, boolean indoor, int capacityPlayers, Integer lengthM, Integer widthM,
			List<String> amenities, BigDecimal fromHourlyPrice, String currency, int slotMinutes, boolean hasPhoto) {
	}

	private final BusinessRepository businesses;
	private final BranchRepository branches;
	private final PitchRepository pitches;
	private final BranchOpeningHoursRepository openingHours;
	private final BranchSpecialDayRepository specialDays;
	private final PriceRuleRepository priceRules;
	private final EntityManager em;

	public CatalogService(BusinessRepository businesses, BranchRepository branches, PitchRepository pitches,
			BranchOpeningHoursRepository openingHours, BranchSpecialDayRepository specialDays,
			PriceRuleRepository priceRules, EntityManager em) {
		this.businesses = businesses;
		this.branches = branches;
		this.pitches = pitches;
		this.openingHours = openingHours;
		this.specialDays = specialDays;
		this.priceRules = priceRules;
		this.em = em;
	}

	public PitchContext pitchContext(Long pitchId) {
		Pitch pitch = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = branchContext(pitch.getBranchId());
		return new PitchContext(pitch, bc.branch(), bc.business());
	}

	/** Müşteri tarafı: kapalı/askıdaki işletmenin sahası "bulunamadı" olarak görünür. */
	public PitchContext publicPitch(Long pitchId) {
		PitchContext ctx = pitchContext(pitchId);
		if (!ctx.publiclyBookable()) {
			throw new NotFoundException("Saha");
		}
		return ctx;
	}

	public BranchContext branchContext(Long branchId) {
		Branch branch = branches.findById(branchId).orElseThrow(() -> new NotFoundException("Şube"));
		Business business = businesses.findById(branch.getBusinessId())
			.orElseThrow(() -> new NotFoundException("İşletme"));
		return new BranchContext(branch, business);
	}

	public List<Pitch> activePitches(Long branchId) {
		return pitches.findByBranchIdAndActiveTrueOrderByName(branchId);
	}

	/**
	 * Şubenin çalışma takvimi. Özel günler [from-1, to] aralığında yüklenir; bir önceki gün
	 * gece yarısını aşan pencereler için gerekir.
	 */
	public BranchSchedule schedule(Branch branch, LocalDate from, LocalDate to) {
		Map<DayOfWeek, DayHours> weekly = new EnumMap<>(DayOfWeek.class);
		for (BranchOpeningHours h : openingHours.findByBranchId(branch.getId())) {
			weekly.put(h.day(), h.hours());
		}
		Map<LocalDate, DayHours> special = new HashMap<>();
		for (BranchSpecialDay d : specialDays.findByBranchIdAndDayBetween(branch.getId(), from.minusDays(1), to)) {
			special.put(d.getDay(), d.hours());
		}
		return new BranchSchedule(branch.zone(), weekly, special);
	}

	public List<PriceRule> priceRules(Long pitchId) {
		return priceRules.findByPitchIdAndActiveTrue(pitchId);
	}

	/** Müşterinin görebileceği tüm sahalar; isteğe bağlı şehir süzgeci. */
	public List<PitchCard> publicPitchCards(String city) {
		List<Object[]> rows = em.createQuery("""
				select p, b, bu from Pitch p, Branch b, Business bu
				where p.branchId = b.id and b.businessId = bu.id
				  and p.active = true and b.archived = false and bu.status = :active
				  and (:city is null or b.city = :city)
				order by b.city, bu.name, b.name, p.name""", Object[].class)
			.setParameter("active", Business.Status.ACTIVE)
			.setParameter("city", city == null || city.isBlank() ? null : city)
			.setMaxResults(200)
			.getResultList();
		List<Long> pitchIds = rows.stream().map(r -> ((Pitch) r[0]).getId()).toList();
		Map<Long, List<PriceRule>> rulesByPitch = new HashMap<>();
		if (!pitchIds.isEmpty()) {
			for (PriceRule rule : priceRules.findByPitchIdInAndActiveTrue(pitchIds)) {
				rulesByPitch.computeIfAbsent(rule.getPitchId(), k -> new ArrayList<>()).add(rule);
			}
		}
		return rows.stream().map(r -> {
			Pitch p = (Pitch) r[0];
			Branch b = (Branch) r[1];
			Business bu = (Business) r[2];
			BigDecimal from = Stream
				.concat(Stream.of(p.getBaseHourlyPrice()),
						rulesByPitch.getOrDefault(p.getId(), List.of()).stream().map(PriceRule::getHourlyPrice))
				.min(Comparator.naturalOrder())
				.orElseThrow();
			return new PitchCard(p.getId(), p.getName(), b.getName(), bu.getName(), b.getDistrict(), b.getCity(),
					p.getSurface().label(), p.isIndoor(), p.getCapacityPlayers(), p.getLengthM(), p.getWidthM(),
					amenities(p), from, p.getCurrency(), p.getSlotMinutes(), p.getPhotoPath() != null);
		}).toList();
	}

	public List<String> cities() {
		return em.createQuery("""
				select distinct b.city from Branch b, Business bu
				where b.businessId = bu.id and b.archived = false and bu.status = :active
				order by b.city""", String.class)
			.setParameter("active", Business.Status.ACTIVE)
			.getResultList();
	}

	public static List<String> amenities(Pitch p) {
		List<String> list = new ArrayList<>();
		list.add(p.isIndoor() ? "Kapalı saha" : "Açık saha");
		if (p.isHasLighting()) {
			list.add("Aydınlatma");
		}
		if (p.isHasParking()) {
			list.add("Otopark");
		}
		if (p.isHasShower()) {
			list.add("Duş");
		}
		if (p.isHasLockerRoom()) {
			list.add("Soyunma odası");
		}
		return list;
	}

}
