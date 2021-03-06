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

package org.entrystore.rest.auth;

import java.util.Date;
import java.util.Map;

/**
* @author Hannes Ebner
*/
public class SignupInfo {
	public String firstName;
	public String lastName;
	public String email;
	public String saltedHashedPassword;
	public Date expirationDate;
	public String urlSuccess;
	public String urlFailure;
	public Map<String, String> customProperties;
}