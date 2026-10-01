package com.sahahub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.community.app.ListingQueries;
import com.sahahub.community.app.ListingService;
import com.sahahub.community.app.TeamService;
import com.sahahub.community.domain.Listing;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;

/** İlanlarda takım logosu: liste, ilan sayfası, başvurular, "İlanlarım"; sürümlü bağlantı; dağılan takım. */
@IntegrationTest
@AutoConfigureMockMvc
class ListingLogoIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	TeamService teams;

	@Autowired
	ListingService listings;

	@Autowired
	ListingQueries queries;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	static MockMultipartFile png(int rgb) throws Exception {
		BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 200; x++) {
			for (int y = 0; y < 200; y++) {
				img.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return new MockMultipartFile("dosya", "logo.png", "image/png", out.toByteArray());
	}

	Long opponentListing(AppUserPrincipal captain, Long teamId) {
		return listings.create(captain, new ListingService.CreateCommand(Listing.Kind.OPPONENT_WANTED, teamId, null,
				"İstanbul", null, DAY.atTime(21, 0), null, Listing.Level.INTERMEDIATE, null));
	}

	@Test
	void logosOnListingPages_versionedUrl_hiddenAfterDisband() throws Exception {
		AppUserPrincipal capA = data.customer();
		AppUserPrincipal capB = data.customer();
		AppUserPrincipal capC = data.customer();
		Long a = teams.create(capA, "Logolu Takım", "İstanbul");
		Long b = teams.create(capB, "Başvuran Takım", "İstanbul");
		Long c = teams.create(capC, "Logosuz Takım", "İstanbul");
		teams.uploadLogo(capA, a, png(0xB42318));
		teams.uploadLogo(capB, b, png(0x1D4ED8));
		Long la = opponentListing(capA, a);
		opponentListing(capC, c);
		listings.apply(capB, la, "Oynarız", b);

		String v1 = queries.detail(null, la).card().team().logoVersion();
		assertThat(v1).isNotNull();
		String logoA = "/takim-logo/" + a + "?v=" + v1;
		mvc.perform(get("/ilanlar")).andExpect(status().isOk())
			.andExpect(content().string(containsString(logoA)))
			.andExpect(content().string(containsString("Logosuz Takım")))
			.andExpect(content().string(not(containsString("/takim-logo/" + c))));
		// İlan sahibi başvuran takımın logosunu da görür
		mvc.perform(get("/ilanlar/{id}", la).with(user(capA))).andExpect(status().isOk())
			.andExpect(content().string(containsString(logoA)))
			.andExpect(content().string(containsString("/takim-logo/" + b + "?v=")));
		mvc.perform(get("/ilanlarim").with(user(capB))).andExpect(status().isOk())
			.andExpect(content().string(containsString(logoA)));
		mvc.perform(get(logoA)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));

		// Logo değişince bağlantı da değişir (tarayıcı önbelleğindeki eski logo gösterilmez)
		teams.uploadLogo(capA, a, png(0x027A48));
		String v2 = queries.detail(null, la).card().team().logoVersion();
		assertThat(v2).isNotEqualTo(v1);
		mvc.perform(get("/takimlar/{id}", a).with(user(capA)))
			.andExpect(content().string(containsString("/takim-logo/" + a + "?v=" + v2)));

		teams.disband(capA, a);
		assertThat(queries.mine(capA)).singleElement().satisfies(x -> assertThat(x.team().hasLogo()).isFalse());
		mvc.perform(get("/ilanlarim").with(user(capA)))
			.andExpect(content().string(not(containsString("/takim-logo/" + a))));
	}

}
