package com.sahahub.booking.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationPriceLineRepository extends JpaRepository<ReservationPriceLine, Long> {

	List<ReservationPriceLine> findByReservationIdOrderByLineNo(Long reservationId);

}
