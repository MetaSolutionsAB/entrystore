package org.entrystore.rest.standalone.springboot.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;

import java.util.function.Supplier;

@RequiredArgsConstructor
public class SupplierDeferredSecurityContext implements DeferredSecurityContext {

	private final Supplier<SecurityContext> supplier;
	private SecurityContext securityContext;

	@Override
	public SecurityContext get() {
		if (this.securityContext == null) {
			this.securityContext = this.supplier.get();
		}
		return this.securityContext;
	}

	@Override
	public boolean isGenerated() {
		return this.securityContext != null;
	}
}
