package com.sahahub.identity.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	@Query("select u from AppUser u where lower(u.email) = lower(:email)")
	Optional<AppUser> findByEmail(String email);

	@Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
	boolean existsByEmail(String email);

}
