package com.sahahub.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Kayıt formu. Doğrulama mesajları alanın yanında gösterilir. */
public class RegistrationForm {

	@NotBlank(message = "Adınızı ve soyadınızı yazın.")
	@Size(max = 120, message = "En fazla 120 karakter.")
	private String fullName;

	@NotBlank(message = "E-posta adresinizi yazın.")
	@Email(message = "Geçerli bir e-posta adresi yazın.")
	@Size(max = 254, message = "E-posta çok uzun.")
	private String email;

	@Pattern(regexp = "^$|^[0-9 +()-]{10,20}$", message = "Telefonu 0532 123 45 67 biçiminde yazın.")
	private String phone;

	@NotBlank(message = "Bir parola belirleyin.")
	@Size(min = 10, max = 72, message = "Parola 10 ile 72 karakter arasında olmalı.")
	private String password;

	public String getFullName() {
		return fullName;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

}
