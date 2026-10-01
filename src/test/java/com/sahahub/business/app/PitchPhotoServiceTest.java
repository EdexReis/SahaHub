package com.sahahub.business.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.sahahub.shared.domain.BusinessRuleException;

/** Fotoğraf doğrulama ve yeniden kodlama (dosya sistemi ve veritabanı yok). */
class PitchPhotoServiceTest {

	static byte[] image(String format, int w, int h) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format, out);
		return out.toByteArray();
	}

	@Test
	void pngIsReencodedAsJpegAndDownscaled() throws IOException {
		byte[] out = PitchPhotoService.normalize(image("png", 2400, 1500));
		assertThat(PitchPhotoService.isJpeg(out)).isTrue();
		BufferedImage back = ImageIO.read(new ByteArrayInputStream(out));
		assertThat(back.getWidth()).isEqualTo(1600);
		assertThat(back.getHeight()).isEqualTo(1000);
	}

	@Test
	void jpegMetadataIsDropped() throws IOException {
		byte[] jpeg = image("jpg", 800, 500);
		// Sahte bir EXIF (APP1) bölümü ekle: FFD8 + FFE1 <uzunluk> "Exif\0\0" + konum benzeri içerik
		byte[] payload = "Exif\0\0GPS 41.0082N 28.9784E".getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream withExif = new ByteArrayOutputStream();
		withExif.write(jpeg, 0, 2);
		withExif.write(0xFF);
		withExif.write(0xE1);
		int len = payload.length + 2;
		withExif.write(len >> 8);
		withExif.write(len & 0xFF);
		withExif.write(payload);
		withExif.write(jpeg, 2, jpeg.length - 2);
		byte[] input = withExif.toByteArray();
		assertThat(new String(input, StandardCharsets.ISO_8859_1)).contains("GPS 41.0082N");

		byte[] out = PitchPhotoService.normalize(input);
		assertThat(new String(out, StandardCharsets.ISO_8859_1)).doesNotContain("GPS").doesNotContain("Exif");
	}

	@Test
	void typeIsDecidedByContentNotByName() throws IOException {
		assertThatThrownBy(() -> PitchPhotoService.normalize(image("gif", 800, 500)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("JPEG veya PNG");
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
		assertThatThrownBy(() -> PitchPhotoService.normalize(svg)).isInstanceOf(BusinessRuleException.class);
		// PNG imzası taşıyan ama bozuk dosya
		byte[] fake = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
		assertThatThrownBy(() -> PitchPhotoService.normalize(fake)).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("okunamadı");
	}

	@Test
	void dimensionsAreCheckedBeforeDecoding() throws IOException {
		assertThatThrownBy(() -> PitchPhotoService.normalize(image("png", 6100, 300)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("en fazla");
		assertThatThrownBy(() -> PitchPhotoService.normalize(image("png", 200, 100)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("en az");
	}

}
