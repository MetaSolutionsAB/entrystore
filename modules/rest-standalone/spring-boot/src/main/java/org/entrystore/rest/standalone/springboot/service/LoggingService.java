package org.entrystore.rest.standalone.springboot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.entrystore.rest.standalone.springboot.model.api.SetLoggingConfigRequestBody;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LoggingService {

	public void updateLoggingConfig(SetLoggingConfigRequestBody config) {

		if (StringUtils.isNotEmpty(config.level())) {
			Level newRootLevel = Level.toLevel(config.level(), Level.INFO);
			log.info("Setting log root level to {}", newRootLevel);
			Configurator.setRootLevel(newRootLevel);
		}

		if (config.packages() != null) {
			config.packages().forEach(
					(pkg, newLevStr) -> {
						Level newLevel = Level.toLevel(newLevStr, Level.INFO);
						log.info("Setting log level of package '{}' to {}", pkg, newLevel);
						Configurator.setLevel(pkg, newLevel);
					});
		}

	}
}
