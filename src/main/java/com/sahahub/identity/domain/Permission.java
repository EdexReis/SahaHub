package com.sahahub.identity.domain;

/**
 * Şube kapsamındaki işlemler. Her kontrolde hem bu izin hem de kullanıcının
 * ilgili şubeye/işletmeye atanmış olması birlikte doğrulanır (bkz. AccessGuard).
 */
public enum Permission {

	CALENDAR_VIEW,
	RESERVATION_CREATE,
	RESERVATION_MOVE,
	RESERVATION_CANCEL,
	RESERVATION_STATUS_UPDATE,
	PITCH_BLOCK_MANAGE,
	PITCH_MANAGE,
	PRICE_MANAGE,
	STAFF_MANAGE,
	REPORT_VIEW,
	AUDIT_VIEW,
	/** Personel indirimi uygulama (gerekçe zorunlu). */
	DISCOUNT_APPLY,
	/** Nakit/manuel POS tahsilatı, havale doğrulama, hatalı kaydı ters çevirme. */
	PAYMENT_COLLECT,
	/** Müşteriye para iadesi. */
	PAYMENT_REFUND,
	/** Kasa açma/kapama. */
	CASH_MANAGE,
	/** Gider kaydı. */
	EXPENSE_MANAGE,
	/** İşletme geneli kupon tanımlama (yalnızca işletme sahibi). */
	COUPON_MANAGE,
	/** Lig açma, takım ekleme, fikstür, maç planlama ve skor girişi. */
	TOURNAMENT_MANAGE

}
