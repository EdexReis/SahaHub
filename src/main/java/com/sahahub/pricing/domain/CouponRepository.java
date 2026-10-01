package com.sahahub.pricing.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	@Query("select c from Coupon c where c.businessId = :businessId and upper(c.code) = upper(:code)")
	Optional<Coupon> findByCode(Long businessId, String code);

	List<Coupon> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

	/**
	 * Kullanım hakkı varsa sayacı atomik olarak artırır; 1 = kullanıldı, 0 = hak kalmadı.
	 * "Önce oku sonra yaz" yerine tek UPDATE: iki istek son hakkı aynı anda alamaz.
	 * clearAutomatically KULLANILMAZ: bağlamı temizlemek aynı işlemdeki kilitli rezervasyonu
	 * bağlamdan koparır ve yeniden hesaplanan toplam kaydedilmez (ReservationPricingIT yakaladı).
	 */
	@Modifying(flushAutomatically = true)
	@Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.id = :id and c.active = true and c.usedCount < c.maxUses")
	int tryUse(Long id);

	/** Rezervasyon iptal edilir veya süresi dolarsa kupon hakkı geri verilir. */
	@Modifying(flushAutomatically = true)
	@Query("update Coupon c set c.usedCount = c.usedCount - 1 where c.id = :id and c.usedCount > 0")
	int release(Long id);

}
