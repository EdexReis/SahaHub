package com.sahahub.payment.app;

/** Rezervasyonun tutma süresi dolduktan / iptal edildikten sonra başarılı ödeme bildirimi geldi. */
public record LatePaymentReceived(Long paymentId) {
}
