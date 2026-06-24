/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.rest.springboot.model.auth.ConfirmAttemptResult;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SignupTokenCacheTest {

	private static final Predicate<SignupInfo> ALWAYS_MATCH = info -> true;
	private static final Predicate<SignupInfo> NEVER_MATCH = info -> false;

	private SignupInfo pendingInfo() {
		SignupInfo info = new SignupInfo();
		info.setEmail("user@example.com");
		info.setExpirationDate(new Date(System.currentTimeMillis() + 3600_000));
		return info;
	}

	@Test
	void confirmAttempt_returnsValidAndConsumesToken_whenCredentialsMatch() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		ConfirmAttemptResult result = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.VALID, result.status());
		assertSame(info, result.info());
		assertNull(cache.getTokenValue("tok"), "a consumed token must be removed");
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forUnknownToken() {
		var cache = new SignupTokenCache();

		ConfirmAttemptResult result = cache.confirmAttempt("missing", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_returnsTokenNotFound_forNullToken() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		// A confirm request that omits the token field arrives as null; it must not throw.
		ConfirmAttemptResult result = cache.confirmAttempt(null, ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, result.status());
	}

	@Test
	void confirmAttempt_countsFailureAndKeepsToken_belowLimit() {
		var cache = new SignupTokenCache();
		SignupInfo info = pendingInfo();
		cache.putToken("tok", info);

		ConfirmAttemptResult first = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.INVALID_CREDENTIALS, first.status());
		assertEquals(2, first.remainingAttempts());
		assertEquals(1, info.getConfirmationAttempts());
		assertSame(info, cache.getTokenValue("tok"), "token must remain usable below the limit");

		ConfirmAttemptResult second = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.INVALID_CREDENTIALS, second.status());
		assertEquals(1, second.remainingAttempts());
	}

	@Test
	void confirmAttempt_invalidatesToken_whenLimitReached() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		ConfirmAttemptResult third = cache.confirmAttempt("tok", NEVER_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_INVALIDATED, third.status());
		assertNull(cache.getTokenValue("tok"), "token must be removed once the attempt limit is reached");
	}

	@Test
	void confirmAttempt_doesNotMatchAfterTokenInvalidated() {
		var cache = new SignupTokenCache();
		cache.putToken("tok", pendingInfo());

		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);
		cache.confirmAttempt("tok", NEVER_MATCH, 3);

		// Even correct credentials cannot complete the flow once the token is gone.
		ConfirmAttemptResult afterInvalidation = cache.confirmAttempt("tok", ALWAYS_MATCH, 3);

		assertEquals(ConfirmAttemptResult.Status.TOKEN_NOT_FOUND, afterInvalidation.status());
	}
}
