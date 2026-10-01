package com.sahahub.pricing.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.CouponRepository;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.pricing.domain.ExtraService;
import com.sahahub.pricing.domain.ExtraServiceRepository;
import com.sahahub.pricing.domain.PriceRule;
import com.sahahub.pricing.domain.PriceRuleRepository;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Fiyatlandırma ekranı: saha taban ücreti, fiyat kuralları, ek hizmetler, kapora politikası (şube)
 * ve kuponlar (işletme geneli, yalnızca sahip). Tüm değişiklikler denetim kaydına yazılır.
 * Değişiklikler yalnızca YENİ rezervasyonları etkiler; mevcut rezervasyonların kalemleri kopyadır.
 */
@Service
public class PricingAdminService {

	public record PricingView(Long branchId, String branchName, Long businessId, List<PitchPricing> pitches,
			List<ExtraRow> extras, DepositPolicy deposit, String bankIban, List<CouponRow> coupons,
			boolean canManageCoupons) {
	}

	public record PitchPricing(Long pitchId, String name, BigDecimal basePrice, List<RuleRow> rules) {
	}

	public record RuleRow(Long id, String name, List<DayOfWeek> days, LocalTime start, LocalTime end,
			LocalDate validFrom, LocalDate validTo, BigDecimal hourlyPrice, int priority) {
	}

	public record ExtraRow(Long id, String name, BigDecimal unitPrice) {

		public static ExtraRow of(ExtraService e) {
			return new ExtraRow(e.getId(), e.getName(), e.getUnitPrice());
		}

	}

	public record CouponRow(Long id, String code, Coupon.Kind kind, BigDecimal value, int usedCount, int maxUses,
			LocalDate validFrom, LocalDate validTo, boolean active) {
	}

	public record RuleCommand(Long pitchId, String name, Set<DayOfWeek> days, LocalTime start, LocalTime end,
			BigDecimal hourlyPrice, int priority, LocalDate validFrom, LocalDate validTo) {
	}

	public record CouponCommand(String code, Coupon.Kind kind, BigDecimal value, int maxUses, LocalDate validFrom,
			LocalDate validTo) {
	}

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final PitchRepository pitches;
	private final BranchRepository branches;
	private final PriceRuleRepository rules;
	private final ExtraServiceRepository extras;
	private final CouponRepository coupons;
	private final AuditService audit;
	private final Clock clock;

	public PricingAdminService(CatalogService catalog, AccessGuard guard, PitchRepository pitches,
			BranchRepository branches, PriceRuleRepository rules, ExtraServiceRepository extras,
			CouponRepository coupons, AuditService audit, Clock clock) {
		this.catalog = catalog;
		this.guard = guard;
		this.pitches = pitches;
		this.branches = branches;
		this.rules = rules;
		this.extras = extras;
		this.coupons = coupons;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PricingView view(AppUserPrincipal staff, Long branchId) {
		BranchContext bc = require(staff, branchId);
		List<PitchPricing> list = new ArrayList<>();
		for (Pitch p : catalog.activePitches(branchId)) {
			List<RuleRow> pr = new ArrayList<>();
			for (PriceRule r : rules.findByPitchIdAndActiveTrue(p.getId())) {
				pr.add(new RuleRow(r.getId(), r.getName(), r.days(), r.getStartTime(), r.getEndTime(), r.getValidFrom(),
						r.getValidTo(), r.getHourlyPrice(), r.getPriority()));
			}
			pr.sort((a, b) -> b.priority() - a.priority());
			list.add(new PitchPricing(p.getId(), p.getName(), p.getBaseHourlyPrice(), pr));
		}
		boolean owner = guard.can(staff, bc.business().getId(), branchId, Permission.COUPON_MANAGE);
		List<CouponRow> couponRows = owner ? coupons.findByBusinessIdOrderByCreatedAtDesc(bc.business().getId())
			.stream()
			.map(c -> new CouponRow(c.getId(), c.getCode(), c.getKind(), c.getValue(), c.getUsedCount(), c.getMaxUses(),
					c.getValidFrom(), c.getValidTo(), c.isActive()))
			.toList() : List.of();
		return new PricingView(branchId, bc.branch().getName(), bc.business().getId(), list,
				extras.findByBranchIdAndActiveTrueOrderByName(branchId).stream().map(ExtraRow::of).toList(),
				bc.branch().depositPolicy(), bc.branch().getBankIban(), couponRows, owner);
	}

	@Transactional
	public void changeBasePrice(AppUserPrincipal staff, Long branchId, Long pitchId, BigDecimal price) {
		BranchContext bc = require(staff, branchId);
		Pitch p = pitchOf(branchId, pitchId);
		requireMoney(price);
		BigDecimal before = p.getBaseHourlyPrice();
		p.changeBasePrice(price);
		audit.record(staff.id(), bc.business().getId(), "PRICE_BASE_CHANGED", "Pitch", pitchId,
				before + " -> " + price);
	}

	@Transactional
	public void addRule(AppUserPrincipal staff, Long branchId, RuleCommand cmd) {
		BranchContext bc = require(staff, branchId);
		pitchOf(branchId, cmd.pitchId());
		if (cmd.name() == null || cmd.name().isBlank()) {
			throw new BusinessRuleException("Kural adını yazın.");
		}
		if (cmd.days() == null || cmd.days().isEmpty()) {
			throw new BusinessRuleException("En az bir gün seçin.");
		}
		if (cmd.start() == null) {
			throw new BusinessRuleException("Başlangıç saatini seçin.");
		}
		if (cmd.end() != null && !cmd.end().isAfter(cmd.start())) {
			throw new BusinessRuleException("Bitiş saati başlangıçtan sonra olmalı (gece yarısına kadar için boş bırakın).");
		}
		if (cmd.validFrom() != null && cmd.validTo() != null && cmd.validTo().isBefore(cmd.validFrom())) {
			throw new BusinessRuleException("Geçerlilik bitişi başlangıçtan önce olamaz.");
		}
		requireMoney(cmd.hourlyPrice());
		PriceRule rule = new PriceRule(cmd.pitchId(), cmd.name().strip(), cmd.days(), cmd.start(), cmd.end(),
				cmd.hourlyPrice(), cmd.priority());
		rule.limitToDates(cmd.validFrom(), cmd.validTo());
		rule = rules.save(rule);
		audit.record(staff.id(), bc.business().getId(), "PRICE_RULE_ADDED", "PriceRule", rule.getId(),
				cmd.name() + ", " + cmd.hourlyPrice() + ", priority=" + cmd.priority());
	}

	@Transactional
	public void deactivateRule(AppUserPrincipal staff, Long branchId, Long ruleId) {
		BranchContext bc = require(staff, branchId);
		PriceRule rule = rules.findById(ruleId).orElseThrow(() -> new NotFoundException("Fiyat kuralı"));
		pitchOf(branchId, rule.getPitchId());
		rule.deactivate();
		audit.record(staff.id(), bc.business().getId(), "PRICE_RULE_DEACTIVATED", "PriceRule", ruleId, rule.getName());
	}

	@Transactional
	public void addExtra(AppUserPrincipal staff, Long branchId, String name, BigDecimal price) {
		BranchContext bc = require(staff, branchId);
		if (name == null || name.isBlank()) {
			throw new BusinessRuleException("Hizmet adını yazın.");
		}
		requireMoney(price);
		ExtraService e = extras.save(new ExtraService(branchId, name, price, Instant.now(clock)));
		audit.record(staff.id(), bc.business().getId(), "EXTRA_SERVICE_ADDED", "ExtraService", e.getId(),
				name + ", " + price);
	}

	@Transactional
	public void deactivateExtra(AppUserPrincipal staff, Long branchId, Long extraId) {
		BranchContext bc = require(staff, branchId);
		ExtraService e = extras.findById(extraId)
			.filter(x -> x.getBranchId().equals(branchId))
			.orElseThrow(() -> new NotFoundException("Ek hizmet"));
		e.deactivate();
		audit.record(staff.id(), bc.business().getId(), "EXTRA_SERVICE_DEACTIVATED", "ExtraService", extraId, null);
	}

	@Transactional
	public void changeDeposit(AppUserPrincipal staff, Long branchId, DepositPolicy.Type type, BigDecimal value,
			String iban) {
		BranchContext bc = require(staff, branchId);
		DepositPolicy policy;
		try {
			policy = new DepositPolicy(type, type == DepositPolicy.Type.NONE ? BigDecimal.ZERO : value);
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessRuleException("Kapora değeri geçersiz: " + ex.getMessage());
		}
		if (policy.type() != DepositPolicy.Type.NONE && !policy.required()) {
			throw new BusinessRuleException("Kapora tutarını veya yüzdesini girin.");
		}
		Branch branch = branches.findById(branchId).orElseThrow();
		branch.changeDepositPolicy(policy);
		branch.changeBankIban(iban);
		audit.record(staff.id(), bc.business().getId(), "DEPOSIT_POLICY_CHANGED", "Branch", branchId,
				type + " " + policy.value());
	}

	@Transactional
	public void addCoupon(AppUserPrincipal staff, Long branchId, CouponCommand cmd) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBusiness(staff, bc.business().getId(), Permission.COUPON_MANAGE);
		if (cmd.code() == null || !cmd.code().strip().matches("[A-Za-z0-9-]{3,30}")) {
			throw new BusinessRuleException("Kupon kodu 3-30 karakter; yalnızca harf, rakam ve tire.");
		}
		requireMoney(cmd.value());
		if (cmd.kind() == Coupon.Kind.PERCENT && cmd.value().compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new BusinessRuleException("Yüzde 100'ü aşamaz.");
		}
		if (cmd.maxUses() < 1) {
			throw new BusinessRuleException("Kullanım sınırı en az 1 olmalı.");
		}
		try {
			Coupon c = coupons.saveAndFlush(new Coupon(bc.business().getId(), cmd.code(), cmd.kind(), cmd.value(),
					cmd.maxUses(), cmd.validFrom(), cmd.validTo(), Instant.now(clock)));
			audit.record(staff.id(), bc.business().getId(), "COUPON_ADDED", "Coupon", c.getId(), c.getCode());
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu kodla bir kupon zaten var.");
		}
	}

	@Transactional
	public void deactivateCoupon(AppUserPrincipal staff, Long branchId, Long couponId) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBusiness(staff, bc.business().getId(), Permission.COUPON_MANAGE);
		Coupon c = coupons.findById(couponId)
			.filter(x -> x.getBusinessId().equals(bc.business().getId()))
			.orElseThrow(() -> new NotFoundException("Kupon"));
		c.deactivate();
		audit.record(staff.id(), bc.business().getId(), "COUPON_DEACTIVATED", "Coupon", couponId, c.getCode());
	}

	private BranchContext require(AppUserPrincipal staff, Long branchId) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(staff, bc.business().getId(), branchId, Permission.PRICE_MANAGE);
		return bc;
	}

	private Pitch pitchOf(Long branchId, Long pitchId) {
		return pitches.findById(pitchId)
			.filter(p -> p.getBranchId().equals(branchId))
			.orElseThrow(() -> new NotFoundException("Saha"));
	}

	private static void requireMoney(BigDecimal v) {
		if (v == null || v.signum() < 0) {
			throw new BusinessRuleException("Geçerli bir tutar girin.");
		}
		if (v.scale() > 2) {
			throw new BusinessRuleException("Tutar en fazla iki ondalık basamak içerebilir.");
		}
	}

}
