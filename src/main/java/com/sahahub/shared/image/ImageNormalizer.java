package com.sahahub.shared.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.sahahub.shared.domain.BusinessRuleException;

/**
 * Kullanıcının yüklediği görüntüyü doğrular ve yeniden kodlar (saha fotoğrafı, takım logosu).
 * <ul>
 * <li>Yalnızca JPEG ve PNG. Tür, istemcinin bildirdiği içerik türüne veya uzantıya değil dosyanın ilk
 * baytlarına ("sihirli sayı") bakılarak belirlenir. SVG kabul edilmez (betik içerebilir).</li>
 * <li>Piksel boyutu görüntü açılmadan başlıktan okunur (çok büyük "sıkıştırma bombası" dosyalarına karşı).</li>
 * <li>Görüntü yeniden çizilip JPEG olarak yazılır: EXIF/konum bilgisi silinir, dosyaya gizlenmiş başka içerik
 * taşınmaz. Saydam PNG beyaz zemine oturur.</li>
 * </ul>
 */
public final class ImageNormalizer {

	public static final int MAX_SIDE = 6000;

	private ImageNormalizer() {
	}

	/**
	 * @param minWidth en küçük genişlik; minHeight en küçük yükseklik
	 * @param targetWidth bundan genişse orantılı küçültülür
	 */
	public static byte[] normalize(byte[] bytes, int minWidth, int minHeight, int targetWidth) {
		if (!isJpeg(bytes) && !isPng(bytes)) {
			throw new BusinessRuleException("Yalnızca JPEG veya PNG görsel yüklenebilir.");
		}
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new BusinessRuleException("Görsel okunamadı.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				if (w > MAX_SIDE || h > MAX_SIDE) {
					throw new BusinessRuleException("Görsel en fazla " + MAX_SIDE + "×" + MAX_SIDE + " piksel olabilir.");
				}
				if (w < minWidth || h < minHeight) {
					throw new BusinessRuleException("Görsel en az " + minWidth + "×" + minHeight + " piksel olmalı.");
				}
				BufferedImage src = reader.read(0);
				int tw = Math.min(targetWidth, w);
				int th = (int) Math.round((double) h * tw / w);
				BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setColor(java.awt.Color.WHITE);
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
			throw new BusinessRuleException("Görsel okunamadı; dosya bozuk olabilir.");
		}
	}

	public static boolean isJpeg(byte[] b) {
		return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
	}

	public static boolean isPng(byte[] b) {
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

	/** Yüklenen dosyanın baytları; boş veya sınırı aşan dosya anlaşılır mesajla reddedilir. */
	public static byte[] bytesOf(org.springframework.web.multipart.MultipartFile file, long maxBytes, String tooLarge) {
		if (file == null || file.isEmpty()) {
			throw new BusinessRuleException("Bir dosya seçin.");
		}
		if (file.getSize() > maxBytes) {
			throw new BusinessRuleException(tooLarge);
		}
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
