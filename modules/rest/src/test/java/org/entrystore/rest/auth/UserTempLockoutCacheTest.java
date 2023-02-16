package org.entrystore.rest.auth;

/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.reflections.Reflections.log;

import org.assertj.core.api.Assertions;
import org.entrystore.Entry;
import org.entrystore.User;
import org.junit.Test;

public class UserTempLockoutCacheTest extends AbstractAuthTest {

	@Test
	public void testNoLockout() {
		UserTempLockoutCache cache = new UserTempLockoutCache(rm, pm);
		User user = mock(User.class);
		Entry entry = mock(Entry.class);
		when(pm.getPrincipalEntry(anyString())).thenReturn(entry);

		for (int i = 1; i <= 4 ; i++) {
			log.info("Fail login");
			cache.failLogin("test");
		}
		Assertions.assertThat(cache.userIsLockedOut("test")).isFalse();
	}

	@Test
	public void testLockout() {
		UserTempLockoutCache cache = new UserTempLockoutCache(rm, pm);
		User user = mock(User.class);
		Entry entry = mock(Entry.class);
		when(pm.getPrincipalEntry(anyString())).thenReturn(entry);

		for (int i = 1; i <= 5 ; i++) {
			log.info("Fail login");
			cache.failLogin("test");
		}
		Assertions.assertThat(cache.userIsLockedOut("test")).isTrue();
	}
}
