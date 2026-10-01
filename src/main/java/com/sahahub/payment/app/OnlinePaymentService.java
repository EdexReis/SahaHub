package com.sahahub.payment.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.HoldExpiredException;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.domain.PaymentSummary;
import com.sahahub.payment.provider.PaymentProvider;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Müşterinin geçici tuttuğu saat için çevrim içi ödeme başlatması (kapora veya tamamı).
 * Ödeme sonucu burada değil, sağlayıcının bildirimiyle (PaymentWebhookService) belli olur.
 */
@Service
public class OnlinePaymentService {

	public enum Option {
		DEPOSIT, FULL
	}

	private final ReservationRepository reservations;
	private final PaymentRepository payments;
	private final PaymentProvider provider;
	private final Clock clock;

	public OnlinePaymentService(ReservationRepository reservations, PaymentRepository payments,
			PaymentProvider provider, Clock clock) {
		this.reservations = reservations;
		this.payments = payments;
		this.provider = provider;
		this.clock = clock;
	}

	/**
	 * Ödeme oturumu açar ve müşterinin yönlendirileceği sağlayıcı adresini döner.
	 * Aynı anahtarla tekrar çağrılırsa (çift tıklama) aynı oturum döner. Rezervasyonda zaten bekleyen
	 * bir çevrim içi ödeme varsa yeni ödeme açılmaz, mevcut olana yönlendirilir.
	 */
	@Transactional
	public String start(AppUserPrincipal user, String code, Option option, String key) {
		Optional<Payment> sameKey = payments.findByIdempotencyKey(key);
		if (sameKey.isPresent()) {
			return redirectOf(sameKey.get());
		}
		Reservation r = reservations.findByCodeForUpdate(code)
			.filter(x -> user.id().equals(x.getCustomerId()))
			.orElseThrow(() -> new NotFoundException("Rezervasyon"));
		if (r.getStatus() != ReservationStatus.HELD) {
			throw new BusinessRuleException("Yalnızca onay bekleyen rezervasyon için çevrim içi ödeme başlatılabilir.");
		}
		Instant now = Instant.now(clock);
		if (r.isHoldExpired(now)) {
			throw new HoldExpiredException();
		}
		List<Payment> list = payments.findByReservationIdOrderByIdAsc(r.getId());
		Optional<Payment> pendingOnline = list.stream()
			.filter(p -> p.getMethod() == Payment.Method.ONLINE_SIM && p.isPending())
			.findFirst();
		if (pendingOnline.isPresent()) {
			return redirectOf(pendingOnline.get());
		}
		PaymentSummary s = PaymentQueries.summarize(r, list);
		BigDecimal amount = option == Option.DEPOSIT ? s.depositRemaining() : s.balance();
		if (amount.signum() <= 0) {
			throw new BusinessRuleException(option == Option.DEPOSIT ? "Bu rezervasyon için kapora gerekmiyor."
					: "Ödenecek tutar kalmadı.");
		}
		Payment p = payments.saveAndFlush(Payment.pendingCharge(r.getBusinessId(), r.getBranchId(), r.getId(),
				Payment.Method.ONLINE_SIM, amount, r.getCurrency(), key, null,
				option == Option.DEPOSIT ? "Kapora" : "Tamamı", user.id(), now));
		PaymentProvider.ChargeSession session = provider.createCharge(key, amount, r.getCurrency(),
				"SahaHub rezervasyon " + r.getCode());
		p.attachProviderRef(session.providerRef());
		return session.redirectPath();
	}

	private String redirectOf(Payment p) {
		if (p.getProviderRef() == null) {
			throw new BusinessRuleException("Bu ödeme çevrim içi değil.");
		}
		return provider.createCharge(p.getIdempotencyKey(), p.getAmount(), p.getCurrency(), "").redirectPath();
	}

	/** Simülasyon sayfasında, ödemenin bu müşteriye ait olduğunu doğrulamak için. */
	@Transactional(readOnly = true)
	public Reservation reservationOfProviderRef(AppUserPrincipal user, String providerRef) {
		Payment p = payments.findByProviderRef(providerRef).orElseThrow(() -> new NotFoundException("Ödeme"));
		Reservation r = reservations.findById(p.getReservationId()).orElseThrow();
		if (!user.id().equals(r.getCustomerId())) {
			throw new NotFoundException("Ödeme");
		}
		return r;
	}

}
