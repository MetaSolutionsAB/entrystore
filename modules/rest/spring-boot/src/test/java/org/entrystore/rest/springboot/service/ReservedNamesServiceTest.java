package org.entrystore.rest.springboot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReservedNamesServiceTest {

	private final ReservedNamesService service = new ReservedNamesService();

	@Test
	void testReservedNameReturnsTrue() {
		assertTrue(service.isReservedName("favicon.ico"));
		assertTrue(service.isReservedName("echo"));
		assertTrue(service.isReservedName("search"));
		assertTrue(service.isReservedName("auth"));
	}

	@Test
	void testNonReservedNameReturnsFalse() {
		assertFalse(service.isReservedName("example"));
		assertFalse(service.isReservedName("test"));
		assertFalse(service.isReservedName("randomname"));
	}

	@Test
	void testNullNameReturnsFalse() {
		assertThrows(NullPointerException.class,
				() -> service.isReservedName(null));
	}
}
