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

package org.entrystore.transforms;

import lombok.Getter;

/**
 * A transform could not finish because a service it calls failed, answered unexpectedly or did not answer in time.
 */
@Getter
public class UpstreamServiceException extends TransformException {

	/** Whether the service did not answer in time, as opposed to failing. */
	private final boolean timeout;

	public UpstreamServiceException(String message, boolean timeout) {
		super(message);
		this.timeout = timeout;
	}
}
