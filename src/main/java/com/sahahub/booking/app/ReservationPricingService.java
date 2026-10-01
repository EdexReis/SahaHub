package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationPriceLine;
import com.sahahub.booking.domain.ReservationPriceLineRepository;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.business.app.CatalogService;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.CouponRepository;
import com.sahahub.pricing.domain.ExtraService;
import com.sahahub.pricing.domain.ExtraServiceRepository;
import com.sahahub.pricing.domain.PriceBreakdown;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Rezervasyon oluşturulduktan sonraki fiyat değişiklikleri: ek hizmet, kupon, personel indirimi.
 * Her değişiklikten sonra tüm etkin kalemler PriceBreakdown sırasıyla yeniden hesaplanır.
 * Saha ücreti kalemleri (anlık görüntü) hiçbir zaman değişmez.
 * <p>
 * Müşteri yalnızca kendi geçici tutmasında (HELD) değişiklik yapabilir; personel açık (HELD/CONFIRMED)
 * rezervasyonlarda yapabilir.
 */
@Service
public class ReservationPricingService {

	private static final int MAX_EXTRA_QUANTITY = 30;

	private final ReservationRepository reservations;
	private final ReservationPriceLineRepository lines;
	private final ExtraServiceRepository extras;
	private final CouponRepository coupons;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final AuditService audit;
	private final Clock clock;

	public ReservationPricingService(ReservationRepository reservations, ReservationPriceLineRepository lines,
			ExtraServiceRepository extras, CouponRepository coupons, CatalogService catalog, AccessGuard guard,
			AuditService audit, Clock clock) {
		this.reservations = reservations;
		this.lines = lines;
		this.extras = extras;
		this.coupons = coupons;
		this.catalog = catalog;
		this.guard = guard;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ ek hizmet

	@Transactional
	public void addExtra(AppUserPrincipal user, String code, Long extraServiceId, int quantity) {
		Reservation r = lockEditable(user, code, Permission.RESERVATION_CREATE);
		if (quantity < 1 || quantity > MAX_EXTRA_QUANTITY) {
			throw new BusinessRuleException("Adet 1 ile " + MAX_EXTRA_QUANTITY + " arasında olmalı.");
		}
		ExtraService extra = extras.findById(extraServiceId)
			.filter(e -> e.isActive() && e.getBranchId().equals(r.getBranchId()))
			.orElseThrow(() -> new NotFoundException("Ek hizmet"));
		lines.save(ReservationPriceLine.extra(r.getId(), nextLineNo(r), extra.getId(), extra.getName(), quantity,
				extra.getUnitPrice(), user.id()));
		recalculate(r);
	}

	// ------------------------------------------------------------------ kupon

	/**
	 * Kuponu uygular. Kullanım hakkı veritabanında atomik olarak düşülür; hak kalmadıysa hata.
	 * Rezervasyonda zaten etkin kupon varsa ikinci kupon kabul edilmez (kısmi tekil indeks de engeller).
	 */
	@Transactional
	public void applyCoupon(AppUserPrincipal user, String code, String couponCode) {
		if (couponCode == null || couponCode.isBlank()) {
			throw new BusinessRuleException("Kupon kodunu yazın.");
		}
		Reservation r = lockEditable(user, code, Permission.RESERVATION_CREATE);
		if (activeLines(r).stream().anyMatch(l -> l.getKind() == PriceBreakdown.Kind.COUPON)) {
			throw new BusinessRuleException("Bu rezervasyonda zaten bir kupon kullanılıyor.");
		}
		Coupon coupon = coupons.findByCode(r.getBusinessId(), Coupon.normalize(couponCode))
			.orElseThrow(() -> new BusinessRuleException("Kupon kodu geçersiz."));
		LocalDate day = r.getStartsAt().atZone(catalog.branchContext(r.getBranchId()).branch().zone()).toLocalDate();
		if (!coupon.validOn(day)) {
			throw new BusinessRuleException("Bu kupon seçtiğiniz tarih için geçerli değil.");
		}
		if (coupons.tryUse(coupon.getId()) == 0) {
			throw new BusinessRuleException("Bu kuponun kullanım hakkı doldu.");
		}
		boolean percent = coupon.getKind() == Coupon.Kind.PERCENT;
		try {
			lines.saveAndFlush(ReservationPriceLine.coupon(r.getId(), nextLineNo(r), coupon.getId(), coupon.getCode(),
					percent ? coupon.getValue() : null, percent ? null : coupon.getValue(), user.id()));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu rezervasyonda zaten bir kupon kullanılıyor.");
		}
		recalculate(r);
	}

	// ------------------------------------------------------------------ personel indirimi

	@Transactional
	public void applyStaffDiscount(AppUserPrincipal staff, String code, BigDecimal percent, BigDecimal amount,
			String reason) {
		Reservation r = lockForStaff(staff, code, Permission.DISCOUNT_APPLY);
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("İndirim gerekçesini yazın.");
		}
		boolean hasPercent = percent != null && percent.signum() > 0;
		boolean hasAmount = amount != null && amount.signum() > 0;
		if (hasPercent == hasAmount) {
			throw new BusinessRuleException("Yüzde veya tutar alanlarından yalnızca birini doldurun.");
		}
		if (hasPercent && percent.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new BusinessRuleException("İndirim yüzdesi 100'ü aşamaz.");
		}
		lines.save(ReservationPriceLine.staffDiscount(r.getId(), nextLineNo(r), hasPercent ? percent : null,
				hasAmount ? amount : null, reason.strip(), staff.id()));
		BigDecimal before = r.getTotalAmount();
		recalculate(r);
		audit.record(staff.id(), r.getBusinessId(), "RESERVATION_STAFF_DISCOUNT", "Reservation", r.getId(),
				"code=" + code + ", before=" + before + ", after=" + r.getTotalAmount() + ", reason=" + reason.strip());
	}

	// ------------------------------------------------------------------ kalem kaldırma

	@Transactional
	public void voidLine(AppUserPrincipal user, String code, Long lineId) {
		Reservation r = lockEditable(user, code, Permission.RESERVATION_CREATE);
		ReservationPriceLine line = lines.findById(lineId)
			.filter(l -> l.getReservationId().equals(r.getId()) && !l.isVoided())
			.orElseThrow(() -> new NotFoundException("Kalem"));
		if (line.getKind() == PriceBreakdown.Kind.PITCH) {
			throw new BusinessRuleException("Saha ücreti kaldırılamaz.");
		}
		if (line.getKind() == PriceBreakdown.Kind.STAFF_DISCOUNT) {
			guard.requireBranch(user, r.getBusinessId(), r.getBranchId(), Permission.DISCOUNT_APPLY);
		}
		line.voidLine(user.id(), Instant.now(clock));
		if (line.getCouponId() != null) {
			coupons.release(line.getCouponId());
		}
		recalculate(r);
		if (line.getKind() != PriceBreakdown.Kind.EXTRA) {
			audit.record(user.id(), r.getBusinessId(), "RESERVATION_PRICE_LINE_VOIDED", "Reservation", r.getId(),
					"code=" + code + ", line=" + line.getLabel());
		}
	}

	/**
	 * Rezervasyon iptal edildiğinde veya süresi dolduğunda kupon hakkını geri verir.
	 * Çağıran işlemin transaction'ı içinde çalışır.
	 */
	@Transactional
	public void releaseCoupons(Long reservationId) {
		for (ReservationPriceLine l : lines.findByReservationIdOrderByLineNo(reservationId)) {
			if (l.getCouponId() != null && !l.isVoided()) {
				coupons.release(l.getCouponId());
			}
		}
	}

	// ------------------------------------------------------------------ yardımcılar

	private void recalculate(Reservation r) {
		r.recalculate(activeLines(r), Instant.now(clock));
	}

	private List<ReservationPriceLine> activeLines(Reservation r) {
		return lines.findByReservationIdOrderByLineNo(r.getId()).stream().filter(l -> !l.isVoided()).toList();
	}

	private int nextLineNo(Reservation r) {
		return lines.findByReservationIdOrderByLineNo(r.getId()).stream()
			.mapToInt(ReservationPriceLine::getLineNo)
			.max()
			.orElse(0) + 1;
	}

	/**
	 * Müşteri kendi HELD rezervasyonunu, personel (izinle) şubesindeki açık rezervasyonu düzenleyebilir.
	 * Rezervasyon satırı kilitlenir: eşzamanlı iki değişiklik toplamı bozamaz.
	 */
	private Reservation lockEditable(AppUserPrincipal user, String code, Permission staffPermission) {
		Reservation r = reservations.findByCodeForUpdate(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		boolean ownReservation = user.id().equals(r.getCustomerId());
		if (ownReservation && r.getStatus() == ReservationStatus.HELD) {
			return r;
		}
		if (guard.can(user, r.getBusinessId(), r.getBranchId(), staffPermission)) {
			return r;
		}
		if (ownReservation) {
			throw new BusinessRuleException("Onaylanmış rezervasyonda değişiklik için şubeyle iletişime geçin.");
		}
		// Başkasının rezervasyonu: varlığını da açık etme
		throw new NotFoundException("Rezervasyon");
	}

	private Reservation lockForStaff(AppUserPrincipal user, String code, Permission permission) {
		Reservation r = reservations.findByCodeForUpdate(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(user, r.getBusinessId(), r.getBranchId(), permission);
		return r;
	}

}
