package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.business.domain.PitchBlockRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Bakım/etkinlik kapatmaları. Kapatma da bir doluluk kaydıdır; mevcut bir rezervasyonun
 * üzerine kapatma eklenemez (önce rezervasyon taşınmalı veya iptal edilmelidir).
 */
@Service
public class PitchBlockService {

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final PitchBlockRepository blocks;
	private final OccupancyService occupancy;
	private final AuditService audit;
	private final Clock clock;

	public PitchBlockService(CatalogService catalog, AccessGuard guard, PitchBlockRepository blocks,
			OccupancyService occupancy, AuditService audit, Clock clock) {
		this.catalog = catalog;
		this.guard = guard;
		this.blocks = blocks;
		this.occupancy = occupancy;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public Long create(AppUserPrincipal user, Long pitchId, LocalDateTime from, LocalDateTime to,
			PitchBlock.Reason reason, String note) {
		PitchContext ctx = catalog.pitchContext(pitchId);
		guard.requireBranch(user, ctx.business().getId(), ctx.branch().getId(), Permission.PITCH_BLOCK_MANAGE);
		if (from == null || to == null || !to.isAfter(from)) {
			throw new BusinessRuleException("Kapatmanın bitişi başlangıcından sonra olmalı.");
		}
		Instant now = Instant.now(clock);
		TimeRange range = new TimeRange(from.atZone(ctx.branch().zone()).toInstant(),
				to.atZone(ctx.branch().zone()).toInstant());
		if (!range.end().isAfter(now)) {
			throw new BusinessRuleException("Geçmişte kalan bir aralık kapatılamaz.");
		}
		String cleanNote = note == null || note.isBlank() ? null : note.strip();
		PitchBlock block = blocks.save(new PitchBlock(pitchId, range, reason, cleanNote, user.id(), now));
		try {
			occupancy.occupy(pitchId, range, PitchOccupancy.Source.BLOCK, block.getId());
		}
		catch (SlotUnavailableException ex) {
			throw new BusinessRuleException(
					"Bu aralıkta rezervasyon veya başka bir kapatma var. Önce rezervasyonu taşıyın ya da iptal edin.");
		}
		audit.record(user.id(), ctx.business().getId(), "PITCH_BLOCK_CREATED", "PitchBlock", block.getId(),
				"pitch=" + pitchId + ", " + range.start() + "/" + range.end() + ", reason=" + reason);
		return block.getId();
	}

	@Transactional
	public void cancel(AppUserPrincipal user, Long blockId) {
		PitchBlock block = blocks.findById(blockId).orElseThrow(() -> new NotFoundException("Kapatma"));
		PitchContext ctx = catalog.pitchContext(block.getPitchId());
		guard.requireBranch(user, ctx.business().getId(), ctx.branch().getId(), Permission.PITCH_BLOCK_MANAGE);
		if (block.isCancelled()) {
			return;
		}
		block.cancel();
		occupancy.release(PitchOccupancy.Source.BLOCK, blockId);
		audit.record(user.id(), ctx.business().getId(), "PITCH_BLOCK_CANCELLED", "PitchBlock", blockId, null);
	}

}
