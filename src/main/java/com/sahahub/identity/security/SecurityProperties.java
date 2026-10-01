package com.sahahub.identity.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml içindeki sahahub.security.* ayarları. */
@ConfigurationProperties("sahahub.security")
public record SecurityProperties(int loginMaxFailures, Duration loginLockDuration) {
}
