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

package org.entrystore.repository.security;

/**
 * Thrown when a list would be moved to another context while a tree it belongs to or holds is being removed or
 * moved, or removed while a tree it belongs to is.
 */
public class TreeRemovalPendingException extends DisallowedException {

	public TreeRemovalPendingException(String message) {
		super(message);
	}
}
