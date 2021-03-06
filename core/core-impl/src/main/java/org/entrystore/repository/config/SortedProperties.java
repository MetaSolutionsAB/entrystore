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

package org.entrystore.repository.config;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * Based on java.util.Properties, provides sorted keys.
 * 
 * @author Hannes Ebner
 * @version $Id$
 */
public class SortedProperties extends Properties {

	public SortedProperties() {
		super();
	}

	public SortedProperties(Properties defaults) {
		super(defaults);
	}

	public synchronized Enumeration keys() {
		List keyList = Collections.list(super.keys());
		Collections.sort(keyList);
		return Collections.enumeration(keyList);
	}

}