package org.entrystore.rest.standalone.springboot.configuration;

import lombok.Getter;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Getter
@Component
public class AppStartedListener implements ApplicationListener<ApplicationStartedEvent> {

	private Instant startupTime;

	@Override
	public void onApplicationEvent(ApplicationStartedEvent event) {
		startupTime = Instant.now();
	}
}
