package com.sahahub.booking.app;

import java.sql.SQLException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.PitchOccupancyRepository;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.shared.domain.TimeRange;

import jakarta.persistence.EntityManager;

/**
 * Sahayı meşgul etme/serbest bırakma işlemlerinin tek yeri.
 * <p>
 * Neden "önce sorgula, sonra kaydet" yetmez? İki istek aynı anda "bu saat boş mu?"
 * diye sorarsa ikisi de "boş" cevabını alır ve ikisi de kaydeder. Bu yüzden kaydı
 * doğrudan ekleriz ve çakışmayı PostgreSQL'in EXCLUDE kısıtına bırakırız: ikinci
 * ekleme, ilki commit edilene kadar bekler, sonra 23P01 hatasıyla reddedilir.
 */
@Service
public class OccupancyService {

	/** PostgreSQL exclusion_violation hata kodu. */
	static final String EXCLUSION_VIOLATION = "23P01";

	/** Advisory kilit ad alanı: başka amaçla alınan kilitlerle karışmasın diye sabit bir sayı ("SH"). */
	static final int ADVISORY_LOCK_NAMESPACE = 0x5348;

	private final PitchOccupancyRepository repository;
	private final EntityManager entityManager;

	public OccupancyService(PitchOccupancyRepository repository, EntityManager entityManager) {
		this.repository = repository;
		this.entityManager = entityManager;
	}

	/**
	 * Sahayı verilen aralık için meşgul eder. Çakışma varsa {@link SlotUnavailableException}.
	 * Mutlaka çağıranın transaction'ı içinde çalışır; hata olursa tüm işlem geri alınır.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void occupy(Long pitchId, TimeRange range, PitchOccupancy.Source source, Long sourceId) {
		lockPitch(pitchId);
		try {
			// saveAndFlush: INSERT hemen çalışsın ki kısıt ihlali burada yakalanabilsin
			repository.saveAndFlush(new PitchOccupancy(pitchId, range, source, sourceId));
		}
		catch (DataIntegrityViolationException ex) {
			if (isExclusionViolation(ex)) {
				throw new SlotUnavailableException();
			}
			throw ex;
		}
	}

	/**
	 * Aynı sahaya aynı anda yapılan eklemeleri sıraya sokar (transaction bitince kilit kendiliğinden
	 * bırakılır). Kilit olmadan da çift rezervasyon OLUŞMAZ — EXCLUDE kısıtı bunu garanti eder —
	 * ancak iki işlem birbirinin henüz commit edilmemiş kaydını beklediğinde PostgreSQL bunu
	 * deadlock olarak çözer: kaybeden ~1 sn sonra "saat dolu" yerine 40P01 hatası alır.
	 * Kilitle ikinci istek ilki bitene kadar bekler, sonra temiz bir 23P01 ile reddedilir.
	 * (ReservationConcurrencyIT bu davranışı doğrular.)
	 */
	private void lockPitch(Long pitchId) {
		entityManager.createNativeQuery("select 1 from (select pg_advisory_xact_lock(:ns, :pitch)) as locked")
			.setParameter("ns", ADVISORY_LOCK_NAMESPACE)
			.setParameter("pitch", Math.toIntExact(pitchId))
			.getSingleResult();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void release(PitchOccupancy.Source source, Long sourceId) {
		repository.deactivate(source, sourceId);
	}

	static boolean isExclusionViolation(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

}
