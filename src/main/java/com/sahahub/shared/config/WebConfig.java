package com.sahahub.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.sahahub.shared.web.MoneyInputFormatter;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

	/** Formlardaki tutar alanları Türkçe (1.400,50) ve noktalı (1400.50) yazımı kabul eder. */
	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addFormatterForFieldType(java.math.BigDecimal.class, new MoneyInputFormatter());
	}

}
