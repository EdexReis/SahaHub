package com.sahahub.business.app;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.image.ImageNormalizer;
import com.sahahub.shared.image.UploadStore;

/**
 * Saha fotoğrafı. Doğrulama ve yeniden kodlama {@link ImageNormalizer}'da (yalnızca JPEG/PNG, içerikten tür
 * tespiti, boyut sınırı, EXIF silinir); saklama {@link UploadStore}'da (rastgele ad, statik kaynakların dışı).
 * En fazla 3 MB; en az 320×200 piksel; 1600 px genişliğe küçültülür.
 */
@Service
public class PitchPhotoService {

	public static final long MAX_BYTES = 3L * 1024 * 1024;
	static final int MIN_WIDTH = 320;
	static final int MIN_HEIGHT = 200;
	static final int TARGET_WIDTH = 1600;

	private final PitchRepository pitches;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final AuditService audit;
	private final UploadStore store;

	public PitchPhotoService(PitchRepository pitches, CatalogService catalog, AccessGuard guard, AuditService audit,
			UploadStore store) {
		this.pitches = pitches;
		this.catalog = catalog;
		this.guard = guard;
		this.audit = audit;
		this.store = store;
	}

	@Transactional
	public void upload(AppUserPrincipal user, Long pitchId, MultipartFile file) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = catalog.branchContext(p.getBranchId());
		guard.requireBranch(user, bc.business().getId(), p.getBranchId(), Permission.PITCH_MANAGE);
		byte[] jpeg = normalize(ImageNormalizer.bytesOf(file, MAX_BYTES, "Fotoğraf en fazla 3 MB olabilir."));
		String name = store.replace(jpeg, p.getPhotoPath());
		p.changePhoto(name);
		audit.record(user.id(), bc.business().getId(), "PITCH_PHOTO_CHANGED", "Pitch", p.getId(), name);
	}

	@Transactional
	public void remove(AppUserPrincipal user, Long pitchId) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = catalog.branchContext(p.getBranchId());
		guard.requireBranch(user, bc.business().getId(), p.getBranchId(), Permission.PITCH_MANAGE);
		String old = p.getPhotoPath();
		if (old == null) {
			return;
		}
		p.changePhoto(null);
		audit.record(user.id(), bc.business().getId(), "PITCH_PHOTO_REMOVED", "Pitch", p.getId(), old);
		store.removeAfterCommit(old);
	}

	/**
	 * Yetki kontrolü olmadan, aynı doğrulama ve yeniden kodlamadan geçirerek fotoğraf ekler. Yalnızca dev
	 * profilindeki demo veri üreticisi kullanır (kullanıcı isteğinden çağrılmaz).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void attachForDemo(Pitch pitch, byte[] image) {
		pitch.changePhoto(store.replace(normalize(image), null));
	}

	/**
	 * Fotoğrafı okur. Rezervasyona açık sahaların fotoğrafı herkese açıktır; kapalı sahanınkini yalnızca o
	 * şubenin yöneticisi görür. Diğer durumlarda "bulunamadı".
	 */
	@Transactional(readOnly = true)
	public Optional<byte[]> read(AppUserPrincipal user, Long pitchId) {
		CatalogService.PitchContext ctx = catalog.pitchContext(pitchId);
		boolean allowed = ctx.publiclyBookable() || (user != null && guard.can(user, ctx.business().getId(),
				ctx.branch().getId(), Permission.PITCH_MANAGE));
		return allowed ? store.read(ctx.pitch().getPhotoPath()) : Optional.empty();
	}

	static byte[] normalize(byte[] bytes) {
		return ImageNormalizer.normalize(bytes, MIN_WIDTH, MIN_HEIGHT, TARGET_WIDTH);
	}

	static boolean isJpeg(byte[] b) {
		return ImageNormalizer.isJpeg(b);
	}

}
