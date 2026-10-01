package com.sahahub.support;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchOpeningHours;
import com.sahahub.business.domain.BranchOpeningHoursRepository;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.Business;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.business.domain.Surface;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.StaffMembership;
import com.sahahub.identity.domain.StaffMembershipRepository;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;

/** Testler için kısa yoldan kayıt oluşturan yardımcı. Her çağrı yeni, benzersiz kayıtlar üretir. */
public class TestData {

	public static final ZoneId IST = ZoneId.of("Europe/Istanbul");

	/** Bir test senaryosunun işletme + şube + saha üçlüsü ve personeli. */
	public record Venue(Business business, Branch branch, Pitch pitch, AppUserPrincipal owner,
			AppUserPrincipal manager, AppUserPrincipal reception) {
	}

	private final TransactionTemplate tx;
	private final AppUserRepository users;
	private final StaffMembershipRepository memberships;
	private final BusinessRepository businesses;
	private final BranchRepository branches;
	private final BranchOpeningHoursRepository hours;
	private final PitchRepository pitches;
	private final PasswordEncoder encoder;

	public TestData(TransactionTemplate tx, AppUserRepository users, StaffMembershipRepository memberships,
			BusinessRepository businesses, BranchRepository branches, BranchOpeningHoursRepository hours,
			PitchRepository pitches, PasswordEncoder encoder) {
		this.tx = tx;
		this.users = users;
		this.memberships = memberships;
		this.businesses = businesses;
		this.branches = branches;
		this.hours = hours;
		this.pitches = pitches;
		this.encoder = encoder;
	}

	/** Her gün 09:00 - 01:00 (gece yarısını aşan) açık şube, 60 dk saatler, tampon yok, 1000 TL/saat. */
	public Venue venue() {
		return venue(0, 60, 60);
	}

	public Venue venue(int bufferMinutes, int slotMinutes, int stepMinutes) {
		return tx.execute(status -> {
			Instant now = Instant.parse("2026-01-01T00:00:00Z");
			Business b = businesses.save(new Business("Test İşletme " + uid(), null, null, null, now));
			Branch br = branches.save(new Branch(b.getId(), "Şube " + uid(), "Adres", "İlçe", "İl", now));
			for (DayOfWeek d : DayOfWeek.values()) {
				hours.save(new BranchOpeningHours(br.getId(), d, DayHours.open(LocalTime.of(9, 0), LocalTime.of(1, 0))));
			}
			Pitch p = new Pitch(br.getId(), "Saha " + uid(), 14, Surface.ARTIFICIAL_TURF, new BigDecimal("1000.00"), now);
			p.configureSlots(slotMinutes, stepMinutes, bufferMinutes);
			p = pitches.save(p);
			AppUser owner = newUser("sahip");
			memberships.save(StaffMembership.owner(owner.getId(), b.getId(), now));
			AppUser manager = newUser("mudur");
			memberships.save(StaffMembership.forBranch(manager.getId(), b.getId(), br.getId(),
					StaffRole.BRANCH_MANAGER, now));
			AppUser reception = newUser("resepsiyon");
			memberships.save(StaffMembership.forBranch(reception.getId(), b.getId(), br.getId(), StaffRole.RECEPTION,
					now));
			return new Venue(b, br, p, staff(owner), staff(manager), staff(reception));
		});
	}

	/** Aynı şubeye ikinci bir saha ekler. */
	public Pitch secondPitch(Venue v) {
		return tx.execute(status -> pitches.save(new Pitch(v.branch().getId(), "Saha B " + uid(), 12,
				Surface.ARTIFICIAL_TURF, new BigDecimal("900.00"), Instant.parse("2026-01-01T00:00:00Z"))));
	}

	/** Aynı işletmeye ikinci bir şube (personel ataması yok). */
	public Branch secondBranch(Venue v) {
		return tx.execute(status -> {
			Branch br = branches.save(new Branch(v.business().getId(), "Şube 2 " + uid(), "Adres", "İlçe", "İl",
					Instant.parse("2026-01-01T00:00:00Z")));
			for (DayOfWeek d : DayOfWeek.values()) {
				hours.save(new BranchOpeningHours(br.getId(), d, DayHours.open(LocalTime.of(9, 0), LocalTime.of(23, 0))));
			}
			return br;
		});
	}

	public AppUserPrincipal customer() {
		AppUser u = tx.execute(status -> newUser("musteri"));
		return AppUserPrincipal.of(u, false);
	}

	public static final String PASSWORD = "test-parolasi-123";

	private AppUser newUser(String prefix) {
		return users.save(new AppUser(prefix + "-" + uid() + "@test.local", encoder.encode(PASSWORD),
				"Test " + prefix, "0555 000 00 00", Instant.parse("2026-01-01T00:00:00Z")));
	}

	private static AppUserPrincipal staff(AppUser u) {
		return AppUserPrincipal.of(u, true);
	}

	private static String uid() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
