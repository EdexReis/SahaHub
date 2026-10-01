package com.sahahub.identity.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.StaffMembershipRepository;

@Service
public class AppUserDetailsService implements UserDetailsService {

	private final AppUserRepository users;
	private final StaffMembershipRepository memberships;

	public AppUserDetailsService(AppUserRepository users, StaffMembershipRepository memberships) {
		this.users = users;
		this.memberships = memberships;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) {
		return users.findByEmail(email.strip())
				.map(user -> AppUserPrincipal.of(user,
						!memberships.findByUserIdAndActiveTrue(user.getId()).isEmpty()))
				// Mesaj kullanıcıya gösterilmez; giriş sayfası her durumda aynı genel hatayı verir
				.orElseThrow(() -> new UsernameNotFoundException("not found"));
	}

}
