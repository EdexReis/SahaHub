package com.sahahub.identity.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.sahahub.identity.domain.AppUser;

/**
 * Oturumda saklanan kullanıcı bilgisi. Entity yerine bu küçük nesneyi tutarız; böylece
 * oturum veritabanı bağlantısına ihtiyaç duymaz.
 * <p>
 * Şube/işletme yetkileri burada DEĞİL, her istekte AccessGuard ile veritabanından kontrol
 * edilir; bir personelin ataması kaldırılırsa etkisi hemen görülür.
 * <p>
 * CredentialsContainer: Spring Security girişten sonra eraseCredentials() çağırır ve
 * parola özeti oturumda kalmaz.
 */
public final class AppUserPrincipal implements UserDetails, CredentialsContainer {

	private final Long id;
	private final String email;
	private final String fullName;
	private final boolean enabled;
	private final boolean platformAdmin;
	private final boolean staff;
	private String passwordHash;

	public AppUserPrincipal(Long id, String email, String fullName, String passwordHash, boolean enabled,
			boolean platformAdmin, boolean staff) {
		this.id = id;
		this.email = email;
		this.fullName = fullName;
		this.passwordHash = passwordHash;
		this.enabled = enabled;
		this.platformAdmin = platformAdmin;
		this.staff = staff;
	}

	public static AppUserPrincipal of(AppUser user, boolean staff) {
		return new AppUserPrincipal(user.getId(), user.getEmail(), user.getFullName(), user.getPasswordHash(),
				user.isEnabled(), user.isPlatformAdmin(), staff);
	}

	public Long id() {
		return id;
	}

	public String email() {
		return email;
	}

	public String fullName() {
		return fullName;
	}

	public boolean platformAdmin() {
		return platformAdmin;
	}

	public boolean staff() {
		return staff;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
		if (staff) {
			authorities.add(new SimpleGrantedAuthority("ROLE_STAFF"));
		}
		if (platformAdmin) {
			authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
		}
		return authorities;
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}

	/** Oturum kaydında (SessionRegistry) aynı kullanıcının oturumlarını bulabilmek için kimliğe göre eşitlik. */
	@Override
	public boolean equals(Object o) {
		return o instanceof AppUserPrincipal other && id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return id == null ? 0 : id.hashCode();
	}

	@Override
	public String toString() {
		return "AppUserPrincipal[id=" + id + "]";
	}

}
