package com.sahahub.booking.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.TimeRange;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Bir sahanın belirli bir zaman aralığı için ayrılması.
 * <p>
 * Durum değişiklikleri yalnızca bu sınıfın metotlarıyla yapılır (setter yoktur);
 * her metot kendi ön koşulunu kontrol eder. Böylece "tamamlanmış rezervasyonu iptal
 * etmek" gibi geçersiz geçişler kodun hangi yerinden çağrılırsa çağrılsın engellenir.
 * <p>
 * Sahanın gerçekten meşgul olduğunu veritabanı seviyesinde garanti eden kayıt
 * {@link PitchOccupancy}'dir; servis katmanı ikisini aynı transaction içinde günceller.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

	/** Personelin "geldi" işaretleyebileceği en erken an: maçtan bu kadar önce. */
	static final Duration CHECK_IN_WINDOW = Duration.ofHours(1);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(name = "business_id", nullable = false)
	private Long businessId;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(name = "pitch_id", nullable = false)
	private Long pitchId;

	@Column(name = "customer_id")
	private Long customerId;

	@Column(name = "guest_name")
	private String guestName;

	@Column(name = "guest_phone")
	private String guestPhone;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	/** Oluşturma anındaki hazırlık süresi; saha ayarı sonradan değişse de bu kayıt etkilenmez. */
	@Column(name = "buffer_minutes", nullable = false)
	private int bufferMinutes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	@Column(name = "hold_expires_at")
	private Instant holdExpiresAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Channel channel;

	private String note;

	@Column(name = "total_amount", nullable = false)
	private BigDecimal totalAmount;

	@Column(nullable = false)
	private String currency;

	@Column(name = "checked_in_at")
	private Instant checkedInAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancel_reason")
	private String cancelReason;

	@Column(name = "cancelled_by")
	private Long cancelledBy;

	@Column(name = "created_by")
	private Long createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** İyimser kilit: aynı kaydı aynı anda değiştiren iki işlemden biri hata alır. */
	@Version
	private long version;

	protected Reservation() {
	}

	private Reservation(Long businessId, Long branchId, Long pitchId, TimeRange play, int bufferMinutes,
			Channel channel, Long createdBy, Instant now) {
		this.code = ReservationCode.generate();
		this.businessId = businessId;
		this.branchId = branchId;
		this.pitchId = pitchId;
		this.startsAt = play.start();
		this.endsAt = play.end();
		this.bufferMinutes = bufferMinutes;
		this.channel = channel;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
		this.totalAmount = BigDecimal.ZERO;
		this.currency = "TRY";
	}

	/** Müşterinin çevrim içi seçtiği saat: onaylanana kadar geçici olarak tutulur. */
	public static Reservation holdForCustomer(Long businessId, Long branchId, Long pitchId, TimeRange play,
			int bufferMinutes, Long customerId, Duration holdFor, Instant now) {
		Reservation r = new Reservation(businessId, branchId, pitchId, play, bufferMinutes, Channel.ONLINE,
				customerId, now);
		r.customerId = customerId;
		r.status = ReservationStatus.HELD;
		r.holdExpiresAt = now.plus(holdFor);
		return r;
	}

	/**
	 * Personelin telefonla/yüz yüze açtığı kayıt: doğrudan onaylanır.
	 * Müşterinin hesabı varsa customerId, yoksa misafir adı/telefonu kullanılır.
	 */
	public static Reservation confirmedByStaff(Long businessId, Long branchId, Long pitchId, TimeRange play,
			int bufferMinutes, Channel channel, Long customerId, String guestName, String guestPhone, String note,
			Long staffUserId, Instant now) {
		if (customerId == null && (guestName == null || guestName.isBlank())) {
			throw new BusinessRuleException("Müşteri adını girin.");
		}
		Reservation r = new Reservation(businessId, branchId, pitchId, play, bufferMinutes, channel, staffUserId,
				now);
		r.customerId = customerId;
		r.guestName = guestName == null || guestName.isBlank() ? null : guestName.strip();
		r.guestPhone = guestPhone == null || guestPhone.isBlank() ? null : guestPhone.strip();
		r.note = note == null || note.isBlank() ? null : note.strip();
		r.status = ReservationStatus.CONFIRMED;
		return r;
	}

	// ------------------------------------------------------------------ zaman

	/** Maçın kendisi: [başlangıç, bitiş). */
	public TimeRange playRange() {
		return new TimeRange(startsAt, endsAt);
	}

	/** Sahanın meşgul sayıldığı aralık: maç + hazırlık süresi. */
	public TimeRange occupiedRange() {
		return playRange().extendEnd(Duration.ofMinutes(bufferMinutes));
	}

	public boolean isHoldExpired(Instant now) {
		return status == ReservationStatus.HELD && !now.isBefore(holdExpiresAt);
	}

	// ------------------------------------------------------------------ şu an yapılabilir mi?
	// Ekran yalnızca geçerli düğmeleri göstersin diye; komut metotları aynı kuralları uygular.

	public boolean canCheckIn(Instant now) {
		return status == ReservationStatus.CONFIRMED && checkedInAt == null
				&& !now.isBefore(startsAt.minus(CHECK_IN_WINDOW)) && now.isBefore(endsAt);
	}

	public boolean canComplete(Instant now) {
		return status == ReservationStatus.CONFIRMED && !now.isBefore(startsAt);
	}

	public boolean canMarkNoShow(Instant now) {
		return status == ReservationStatus.CONFIRMED && checkedInAt == null && !now.isBefore(startsAt);
	}

	public boolean canReschedule(Instant now) {
		return status.isOpen() && now.isBefore(startsAt);
	}

	public boolean canBeCancelledByStaff(Instant now) {
		return status.isOpen() && now.isBefore(endsAt);
	}

	/** Personelin "geldi" işaretleyebileceği ilk an. */
	public Instant checkInOpensAt() {
		return startsAt.minus(CHECK_IN_WINDOW);
	}

	// ------------------------------------------------------------------ durum geçişleri

	public void confirm(Instant now) {
		requireStatus(ReservationStatus.HELD);
		if (isHoldExpired(now)) {
			throw new HoldExpiredException();
		}
		moveTo(ReservationStatus.CONFIRMED, now);
		this.holdExpiresAt = null;
	}

	/** Tutma süresi dolmuşsa EXPIRED yapar. Süresi dolmamış tutmaya dokunmaz. */
	public void expire(Instant now) {
		if (!isHoldExpired(now)) {
			throw new IllegalStateException("Süresi dolmamış rezervasyon expire edilemez: " + code);
		}
		moveTo(ReservationStatus.EXPIRED, now);
	}

	public void cancel(Long byUserId, String reason, Instant now) {
		if (!status.isOpen()) {
			throw new BusinessRuleException("'" + status.label() + "' durumundaki rezervasyon iptal edilemez.");
		}
		if (!now.isBefore(endsAt)) {
			throw new BusinessRuleException("Bitmiş bir maç iptal edilemez.");
		}
		moveTo(ReservationStatus.CANCELLED, now);
		this.cancelledAt = now;
		this.cancelledBy = byUserId;
		this.cancelReason = reason == null || reason.isBlank() ? null : reason.strip();
		this.holdExpiresAt = null;
	}

	public void checkIn(Instant now) {
		requireStatus(ReservationStatus.CONFIRMED);
		if (checkedInAt != null) {
			throw new BusinessRuleException("Geliş zaten kaydedilmiş.");
		}
		if (!canCheckIn(now)) {
			throw new BusinessRuleException("Geliş, maçtan en fazla 1 saat önce ile maç bitişi arasında kaydedilebilir.");
		}
		this.checkedInAt = now;
		this.updatedAt = now;
	}

	public void complete(Instant now) {
		requireStatus(ReservationStatus.CONFIRMED);
		if (now.isBefore(startsAt)) {
			throw new BusinessRuleException("Maç başlamadan tamamlandı olarak işaretlenemez.");
		}
		moveTo(ReservationStatus.COMPLETED, now);
	}

	public void markNoShow(Instant now) {
		requireStatus(ReservationStatus.CONFIRMED);
		if (checkedInAt != null) {
			throw new BusinessRuleException("Gelişi kaydedilmiş müşteri 'gelmedi' olarak işaretlenemez.");
		}
		if (now.isBefore(startsAt)) {
			throw new BusinessRuleException("Maç saati gelmeden 'gelmedi' işaretlenemez.");
		}
		moveTo(ReservationStatus.NO_SHOW, now);
	}

	/**
	 * Rezervasyonu başka saate/sahaya taşır. Doluluk kaydının güncellenmesi ve çakışma
	 * kontrolü servis katmanında aynı transaction içinde yapılır; çakışma olursa
	 * transaction geri alınır ve bu değişiklik de kaydedilmez.
	 */
	public void reschedule(Long newPitchId, TimeRange newPlay, int newBufferMinutes, Instant now) {
		if (!status.isOpen()) {
			throw new BusinessRuleException("Yalnızca aktif rezervasyonlar taşınabilir.");
		}
		if (!canReschedule(now)) {
			throw new BusinessRuleException("Başlamış bir maç taşınamaz.");
		}
		if (!newPlay.start().isAfter(now)) {
			throw new BusinessRuleException("Geçmiş bir saate taşınamaz.");
		}
		this.pitchId = newPitchId;
		this.bufferMinutes = newBufferMinutes;
		this.startsAt = newPlay.start();
		this.endsAt = newPlay.end();
		this.updatedAt = now;
	}

	public void applyPrice(BigDecimal total, String currency) {
		this.totalAmount = total;
		this.currency = currency;
	}

	private void requireStatus(ReservationStatus expected) {
		if (status != expected) {
			throw new BusinessRuleException("Bu işlem için rezervasyonun durumu '" + expected.label()
					+ "' olmalı; şu anki durum: '" + status.label() + "'.");
		}
	}

	private void moveTo(ReservationStatus next, Instant now) {
		if (!status.canTransitionTo(next)) {
			throw new BusinessRuleException(
					"'" + status.label() + "' durumundan '" + next.label() + "' durumuna geçilemez.");
		}
		this.status = next;
		this.updatedAt = now;
	}

	// ------------------------------------------------------------------ getters

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getPitchId() {
		return pitchId;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public String getGuestName() {
		return guestName;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public int getBufferMinutes() {
		return bufferMinutes;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public Instant getHoldExpiresAt() {
		return holdExpiresAt;
	}

	public Channel getChannel() {
		return channel;
	}

	public String getNote() {
		return note;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getCheckedInAt() {
		return checkedInAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
