package com.sahahub.shared.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Yüklenen görsellerin klasörü (sahahub.upload-dir). Dosya adları sunucuda rastgele üretilir; okuma yalnızca bu
 * biçimdeki adlarla ve klasörün içinde yapılır (dizin dışına çıkma engellenir). Klasör statik kaynakların
 * dışındadır: dosyalar yalnızca erişim kontrolü yapan uçlardan sunulur.
 */
@Component
public class UploadStore {

	private static final Pattern SAFE_NAME = Pattern.compile("[0-9a-f\\-]{36}\\.jpg");
	private static final Logger log = LoggerFactory.getLogger(UploadStore.class);

	private final Path dir;

	public UploadStore(@Value("${sahahub.upload-dir:./data/uploads}") String uploadDir) {
		this.dir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	/**
	 * Yeni JPEG'i yazar ve adını döner. Veritabanı değişikliği geri alınırsa yeni dosya, commit olursa
	 * oldName silinir; böylece kayıtsız dosya birikmez. Çağıranın transaction'ı içinde kullanılır.
	 */
	public String replace(byte[] jpeg, String oldName) {
		String name = UUID.randomUUID() + ".jpg";
		Path file = write(name, jpeg);
		afterTransaction(oldName, file);
		return name;
	}

	/** Dosyayı (commit olursa) siler. */
	public void removeAfterCommit(String oldName) {
		afterTransaction(oldName, null);
	}

	public Optional<byte[]> read(String name) {
		if (name == null || !SAFE_NAME.matcher(name).matches()) {
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
			log.warn("Görsel okunamadı: {}", ex.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	public Path dir() {
		return dir;
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
			throw new UncheckedIOException("Görsel kaydedilemedi", ex);
		}
	}

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
