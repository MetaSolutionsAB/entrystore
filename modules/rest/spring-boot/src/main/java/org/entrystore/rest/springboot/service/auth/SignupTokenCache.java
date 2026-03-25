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

package org.entrystore.rest.springboot.service.auth;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupTokenCache extends TokenCache<String, SignupInfo> {

	@Transactional
	public void cleanup() {
		synchronized (tokenCache) {
			for (Map.Entry<String, SignupInfo> e : tokenCache.entrySet()) {
				if (e.getValue().getExpirationDate().before(new Date())) {
					tokenCache.remove(e.getKey());
				}
			}
		}
	}

	public void removeAllTokens(String userEmail) {
		synchronized (tokenCache) {
			tokenCache.entrySet().removeIf(userInfo -> userEmail.equals(userInfo.getValue().getEmail()));
		}
	}

}
