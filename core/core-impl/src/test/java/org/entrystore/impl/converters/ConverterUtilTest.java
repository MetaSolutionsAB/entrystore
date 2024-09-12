/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.impl.converters;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ConverterUtilTest {

	@Test
	public void findResource_solrconfig() {
		URL resourceURL = ConverterUtil.findResource("solrconfig.xml_default");
		assertNotNull(resourceURL);
	}

	@Test
	public void findResource_schema() {
		URL resourceURL = ConverterUtil.findResource("schema.xml_default");
		assertNotNull(resourceURL);
	}

	@Test
	public void findResource_null() {
		URL resourceURL = ConverterUtil.findResource("anything");
		assertNull(resourceURL);
	}
}
