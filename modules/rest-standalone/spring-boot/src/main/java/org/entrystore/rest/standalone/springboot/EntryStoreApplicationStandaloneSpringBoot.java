package org.entrystore.rest.standalone.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EntryStoreConfiguration.class)
public class EntryStoreApplicationStandaloneSpringBoot {

	public static void main(String[] args) {
		SpringApplication.run(EntryStoreApplicationStandaloneSpringBoot.class, args);
	}
}
