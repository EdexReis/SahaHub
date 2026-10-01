package com.sahahub.booking.app;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationPriceLine;
import com.sahahub.booking.domain.ReservationPriceLineRepository;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.pricing.domain.PriceCalculator;
import com.sahahub.pricing.domain.PriceQuote;

/**
 * Yeni rezervasyonu kaydetmenin ortak adımları (müşteri ve personel akışları paylaşır):
 * fiyatı hesapla → rezervasyonu kaydet → fiyat kalemlerini kopyala → sahayı meşgul et.
 * Hepsi tek transaction'dadır; son adımda çakışma çıkarsa öncekiler de geri alınır.
 */
@Component
class ReservationWriter {

	private final CatalogService catalog;
	private final ReservationRepository reservations;
	private final ReservationPriceLineRepository priceLines;
	private final OccupancyService occupancy;

	ReservationWriter(CatalogService catalog, ReservationRepository reservations,
			ReservationPriceLineRepository priceLines, OccupancyService occupancy) {
		this.catalog = catalog;
		this.reservations = reservations;
		this.priceLines = priceLines;
		this.occupancy = occupancy;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	Reservation persistNew(Reservation reservation, PitchContext ctx) {
		PriceQuote quote = PriceCalculator.quote(reservation.playRange(), ctx.branch().zone(),
				ctx.pitch().getBaseHourlyPrice(), ctx.pitch().getCurrency(), catalog.priceRules(ctx.pitch().getId()));
		reservation.applyPrice(quote.total(), quote.currency());
		Reservation saved = reservations.save(reservation);
		List<PriceQuote.Line> lines = quote.lines();
		for (int i = 0; i < lines.size(); i++) {
			priceLines.save(new ReservationPriceLine(saved.getId(), i + 1, lines.get(i)));
		}
		occupancy.occupy(saved.getPitchId(), saved.occupiedRange(), PitchOccupancy.Source.RESERVATION,
				saved.getId());
		return saved;
	}

}
