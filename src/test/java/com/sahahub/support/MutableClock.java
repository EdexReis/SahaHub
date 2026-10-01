package com.sahahub.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Testlerde zamanı elle ileri almak için saat. Uygulama bu saati Clock bean'i olarak kullanır. */
public class MutableClock extends Clock {

	private volatile Instant now;

	public MutableClock(Instant now) {
		this.now = now;
	}

	public void set(Instant instant) {
		this.now = instant;
	}

	public void advance(Duration duration) {
		this.now = now.plus(duration);
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(now, zone);
	}

}
