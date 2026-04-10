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

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.Date;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
@Getter
@Setter
public class SignupInfo {

	private String firstName;

	private String lastName;

	private String email;

	private String saltedHashedPassword;

	private Date expirationDate;

	private String urlSuccess;

	private String urlFailure;

	private Map<String, String> customProperties;

	public void setEmail(@NonNull String email) {
		// we have to store it in lower case only to avoid problems with different cases in
		// different steps of the process (if the user provides inconsistent information)
		this.email = email.toLowerCase();
	}

}
