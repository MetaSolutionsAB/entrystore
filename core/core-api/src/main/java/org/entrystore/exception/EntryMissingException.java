package org.entrystore.exception;

import java.net.URI;

public class EntryMissingException extends RuntimeException {

	private final URI entryUri;

	public EntryMissingException(URI entryUri) {
		this.entryUri = entryUri;
	}

	public URI getEntryUri() {
		return entryUri;
	}
}
