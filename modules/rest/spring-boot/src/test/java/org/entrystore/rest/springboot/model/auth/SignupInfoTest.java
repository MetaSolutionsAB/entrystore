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

package org.entrystore.rest.springboot.model.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignupInfoTest {

	@Test
	void setEmail_shouldConvertToLowerCase() {
		SignupInfo info = new SignupInfo();

		info.setEmail("User@Example.COM");

		assertEquals("user@example.com", info.getEmail());
	}

	@Test
	void setUrlSuccess_shouldStoreValue() {
		SignupInfo info = new SignupInfo();

		info.setUrlSuccess("http://localhost:8181/success");

		assertEquals("http://localhost:8181/success", info.getUrlSuccess());
	}

	@Test
	void setUrlFailure_shouldStoreValue() {
		SignupInfo info = new SignupInfo();

		info.setUrlFailure("http://localhost:8181/failure");

		assertEquals("http://localhost:8181/failure", info.getUrlFailure());
	}

	@Test
	void newSignupInfo_shouldHaveNullFields() {
		SignupInfo info = new SignupInfo();

		assertNull(info.getEmail());
		assertNull(info.getUrlSuccess());
		assertNull(info.getUrlFailure());
		assertNull(info.getFirstName());
		assertNull(info.getLastName());
	}

}
