package com.sahahub.booking.app;

/**
 * Rezervasyon iptal edildi olayı. Transaction commit olduktan sonra dinlenir (ör. ödeme modülü,
 * müşterinin süresi içinde iptal ettiği çevrim içi ödemeyi otomatik iade eder).
 *
 * @param byCustomer müşteri kendisi mi iptal etti (personel iptalinde iade kararı personeldedir)
 */
public record ReservationCancelled(Long reservationId, boolean byCustomer) {
}
