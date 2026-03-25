package org.entrystore.rest.springboot.service;

import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@NoArgsConstructor
public class ReservedNamesService {

	private final static Set<String> RESERVED_NAMES_SET = Set.of(
			"favicon.ico",
			"echo",
			"lookup",
			"proxy",
			"search",
			"sparql",
			"validator",
			"message",
			"auth",
			"management");

	public boolean isReservedName(String name) {
		return RESERVED_NAMES_SET.contains(name);
	}
}
