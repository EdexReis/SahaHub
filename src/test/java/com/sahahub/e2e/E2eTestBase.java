package com.sahahub.e2e;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sahahub.TestcontainersConfiguration;

/**
 * Uçtan uca testler: uygulama rastgele bir portta, Testcontainers PostgreSQL ve demo veriyle
 * (dev profili) başlar; Playwright gerçek bir Chromium ile kullanıcı gibi gezer.
 * Çalıştırma: mvnw -Pe2e test (ilk çalıştırmada Playwright, Chromium'u indirir).
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = { "sahahub.demo-data=true", "spring.thymeleaf.cache=true" })
@Import(TestcontainersConfiguration.class)
abstract class E2eTestBase {

	static final String DEMO_PASSWORD = "SahaHub.demo1";
	static final Path SCREENSHOTS = Paths.get("target", "screenshots");

	static Playwright playwright;
	static Browser browser;

	@LocalServerPort
	int port;

	BrowserContext context;
	Page page;

	/**
	 * Varsayılan: Playwright'ın indirdiği Chromium (CI). Yerelde kurulu Edge/Chrome kullanmak için:
	 * mvnw -Pe2e test -De2e.channel=msedge (bu durumda tarayıcı indirilmez).
	 */
	@BeforeAll
	static void launchBrowser() {
		String channel = System.getProperty("e2e.channel", "");
		var launch = new BrowserType.LaunchOptions().setHeadless(true);
		if (channel.isBlank()) {
			playwright = Playwright.create();
		}
		else {
			playwright = Playwright.create(new Playwright.CreateOptions()
				.setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
			launch.setChannel(channel);
		}
		browser = playwright.chromium().launch(launch);
	}

	@AfterAll
	static void closeBrowser() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void openContext() {
		context = newContext(1366, 900);
		page = context.newPage();
	}

	@AfterEach
	void closeContext() {
		context.close();
	}

	BrowserContext newContext(int width, int height) {
		return browser.newContext(new Browser.NewContextOptions().setViewportSize(width, height)
			.setLocale("tr-TR")
			.setTimezoneId("Europe/Istanbul")
			.setBaseURL("http://localhost:" + port));
	}

	static void login(Page page, String email) {
		page.navigate("/giris");
		page.getByLabel("E-posta").fill(email);
		page.getByLabel("Parola").fill(DEMO_PASSWORD);
		page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName("Giriş yap")).click();
		page.waitForURL(url -> !url.contains("/giris"));
	}

	static void shot(Page page, String name) {
		page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOTS.resolve(name + ".png")).setFullPage(true));
	}

}
