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

import lombok.Builder;
import lombok.With;

import java.time.LocalDateTime;

/**
 * A session as /auth/tokens reports it. {@code current} is set per response, for the session making the request; the
 * stored instances leave it false.
 */
@Builder
public record SessionInfo(String userName, LocalDateTime loginTime, LocalDateTime loginExpiration,
						  LocalDateTime lastAccessTime, String lastUsedIpAddress, String lastUsedUserAgent,
						  int loginTokenMaxAge, @With boolean current) {

}
