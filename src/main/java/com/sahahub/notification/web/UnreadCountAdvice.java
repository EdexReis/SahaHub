package com.sahahub.notification.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.notification.app.NotificationService;

/** Üst menüdeki "Bildirimler" bağlantısı için okunmamış sayısı. */
@ControllerAdvice(annotations = Controller.class)
class UnreadCountAdvice {

	private final NotificationService service;

	UnreadCountAdvice(NotificationService service) {
		this.service = service;
	}

	@ModelAttribute("unreadCount")
	long unreadCount(@AuthenticationPrincipal AppUserPrincipal me) {
		return me == null ? 0 : service.unreadCount(me.id());
	}

}
