package com.sahahub.business.app;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Saha fotoğrafı.
 * <ul>
 * <li>Yalnızca JPEG ve PNG kabul edilir. Tür, istemcinin bildirdiği içerik türüne veya uzantıya değil dosyanın
 * ilk baytlarına ("sihirli sayı") bakılarak belirlenir. SVG kabul edilmez (betik içerebilir).</li>
 * <li>En fazla 3 MB ve 6000×6000 piksel. Boyut, görüntü tamamen açılmadan başlıktan okunur (çok büyük
 * piksel sayılı "sıkıştırma bombası" dosyalarına karşı).</li>
 * <li>Görüntü yeniden kodlanır (en fazla 1600 px genişlik, JPEG). Böylece EXIF/konum bilgisi silinir ve
 * dosyaya gizlenmiş başka içerik taşınmaz.</li>
 * <li>Dosya adı sunucuda rastgele üretilir; yükleme klasörü uygulamanın statik kaynakları dışındadır ve
 * dosya yalnızca {@link #read} üzerinden, erişim kontrolüyle sunulur.</li>
 * </ul>
 */
@Service
public class PitchPhotoService {

	public static final long MAX_BYTES = 3L * 1024 * 1024;
	static final int MAX_SIDE = 6000;
	static final int MIN_WIDTH = 320;
	static final int MIN_HEIGHT = 200;
	static final int TARGET_WIDTH = 1600;
	private static final Pattern SAFE_NAME = Pattern.compile("[0-9a-f\\-]{36}\\.jpg");
	private static final Logger log = LoggerFactory.getLogger(PitchPhotoService.class);

	private final PitchRepository pitches;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final AuditService audit;
	private final Path dir;

	public PitchPhotoService(PitchRepository pitches, CatalogService catalog, AccessGuard guard, AuditService audit,
			@Value("${sahahub.upload-dir:./data/uploads}") String uploadDir) {
		this.pitches = pitches;
		this.catalog = catalog;
		this.guard = guard;
		this.audit = audit;
		this.dir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	@Transactional
	public void upload(AppUserPrincipal user, Long pitchId, MultipartFile file) {
		Pitch p = pitches.findById(pitchId).orElseThrow(() -> new NotFoundException("Saha"));
		BranchContext bc = catalog.branchContext(p.getBranchId());
		guard.requireBranch(user, bc.business().getId(), p.getBranchId(), Permission.PITCH_MANAGE);
		if (file == null || file.isEmpty()) {
			throw new BusinessRuleException("Bir fotoğraf seçin.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new BusinessRuleException("Fotoğraf en fazla 3 MB olabilir.");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		byte[] jpeg = normalize(bytes);
		String name = UUID.randomUUID() + ".jpg";
		Path target = write(name, jpeg);
		String old = p.getPhotoPath();
		p.changePhoto(name);
		audit.record(user.id(), bc.business().getId(), "PITCH_PHOTO_CHANGED", "Pitch", p.getId(), name);
		afterTransaction(old, target);
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
		afterTransaction(old, null);
	}

	/**
	 * Yetki kontrolü olmadan, aynı doğrulama ve yeniden kodlamadan geçirerek fotoğraf ekler. Yalnızca
	 * dev profilindeki demo veri üreticisi kullanır (kullanıcı isteğinden çağrılmaz).
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void attachForDemo(Pitch pitch, byte[] image) {
		String name = UUID.randomUUID() + ".jpg";
		Path target = write(name, normalize(image));
		pitch.changePhoto(name);
		afterTransaction(null, target);
	}

	/**
	 * Fotoğrafı okur. Rezervasyona açık sahaların fotoğrafı herkese açıktır; kapalı sahanınkini yalnızca
	 * o şubenin yöneticisi görür. Diğer durumlarda "bulunamadı".
	 */
	@Transactional(readOnly = true)
	public Optional<byte[]> read(AppUserPrincipal user, Long pitchId) {
		CatalogService.PitchContext ctx = catalog.pitchContext(pitchId);
		boolean allowed = ctx.publiclyBookable() || (user != null && guard.can(user, ctx.business().getId(),
				ctx.branch().getId(), Permission.PITCH_MANAGE));
		String name = ctx.pitch().getPhotoPath();
		if (!allowed || name == null || !SAFE_NAME.matcher(name).matches()) {
			return Optional.empty();
		}
		Path file = dir.resolve(name).normalize();
		if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(file));
		}
		catch (IOException ex) {
			log.warn("Saha fotoğrafı okunamadı pitch={}: {}", pitchId, ex.toString());
			return Optional.empty();
		}
	}

	// ------------------------------------------------------------------ görüntü işleme

	/** Doğrular ve yeniden kodlar. Sonuç her zaman JPEG'dir. */
	static byte[] normalize(byte[] bytes) {
		if (!isJpeg(bytes) && !isPng(bytes)) {
			throw new BusinessRuleException("Yalnızca JPEG veya PNG fotoğraf yüklenebilir.");
		}
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new BusinessRuleException("Fotoğraf okunamadı.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				if (w > MAX_SIDE || h > MAX_SIDE) {
					throw new BusinessRuleException("Fotoğraf en fazla " + MAX_SIDE + "×" + MAX_SIDE + " piksel olabilir.");
				}
				if (w < MIN_WIDTH || h < MIN_HEIGHT) {
					throw new BusinessRuleException("Fotoğraf en az " + MIN_WIDTH + "×" + MIN_HEIGHT + " piksel olmalı.");
				}
				BufferedImage src = reader.read(0);
				int tw = Math.min(TARGET_WIDTH, w);
				int th = (int) Math.round((double) h * tw / w);
				BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setColor(java.awt.Color.WHITE); // saydam PNG beyaz zemine
				g.fillRect(0, 0, tw, th);
				g.drawImage(src, 0, 0, tw, th, null);
				g.dispose();
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				if (!ImageIO.write(out, "jpg", buf)) {
					throw new IllegalStateException("JPEG yazıcısı yok");
				}
				return buf.toByteArray();
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			if (ex instanceof BusinessRuleException bre) {
				throw bre;
			}
			throw new BusinessRuleException("Fotoğraf okunamadı; dosya bozuk olabilir.");
		}
	}

	static boolean isJpeg(byte[] b) {
		return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
	}

	static boolean isPng(byte[] b) {
		byte[] sig = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		if (b.length < sig.length) {
			return false;
		}
		for (int i = 0; i < sig.length; i++) {
			if (b[i] != sig[i]) {
				return false;
			}
		}
		return true;
	}

	private Path write(String name, byte[] content) {
		try {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "upload-", ".tmp");
			Files.write(tmp, content);
			Path target = dir.resolve(name);
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			return target;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Fotoğraf kaydedilemedi", ex);
		}
	}

	/**
	 * Dosya işlemleri veritabanıyla aynı transaction'da geri alınamaz; bu yüzden commit olursa eski dosya,
	 * geri alınırsa yeni dosya silinir. Böylece kayıtsız dosya birikmez.
	 */
	private void afterTransaction(String oldName, Path newFile) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_COMMITTED && oldName != null && SAFE_NAME.matcher(oldName).matches()) {
					deleteQuietly(dir.resolve(oldName));
				}
				else if (status != STATUS_COMMITTED && newFile != null) {
					deleteQuietly(newFile);
				}
			}
		});
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		}
		catch (IOException ex) {
			log.warn("Dosya silinemedi: {}", p.getFileName());
		}
	}


}
