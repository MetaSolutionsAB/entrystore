/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

package org.entrystore.repository.util;

public class StringUtils {

	public static String replace(String data, String from, String to) {
		StringBuffer buf = new StringBuffer(data.length());
		int pos = -1;
		int i = 0;
		while ((pos = data.indexOf(from, i)) != -1) {
			buf.append(data.substring(i, pos)).append(to);
			i = pos + from.length();
		}
		buf.append(data.substring(i));
		return buf.toString();
	}

}