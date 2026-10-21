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

package org.entrystore.rest.springboot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateSectionsTest {

	private static final Set<String> NAMES = Set.of("new-account", "existing-account");

	private static final String TWO_SECTIONS = "<head/>"
			+ "<!--BEGIN new-account--><p>Confirm $1 \\</p><!--END new-account-->"
			+ "<!--BEGIN existing-account--><p>Exists</p><!--END existing-account-->"
			+ "<footer/>";

	@Test
	void split_keepsEachSectionWithoutItsMarkersAndRemovesTheOtherWhole() {
		Map<String, String> mails = TemplateSections.split(TWO_SECTIONS, NAMES);

		assertEquals("<head/><p>Confirm $1 \\</p><footer/>", mails.get("new-account"));
		assertEquals("<head/><p>Exists</p><footer/>", mails.get("existing-account"));
	}

	@Test
	void split_returnsOnlyTheSectionsTheTemplateHas() {
		Map<String, String> mails = TemplateSections.split("<!--BEGIN new-account-->x<!--END new-account-->", NAMES);

		assertEquals(Map.of("new-account", "x"), mails);
	}

	@Test
	void split_templateWithoutMarkers_hasNoSections() {
		assertTrue(TemplateSections.split("<p>Confirm __CONFIRMATION_LINK__</p>", NAMES).isEmpty());
	}

	@Test
	void split_keepsOrdinaryCommentsInEveryMail() {
		String template = "<!-- end content --><!--[if mso]><table><![endif]-->"
				+ "<!--BEGIN new-account-->x<!--END new-account-->";

		assertEquals("<!-- end content --><!--[if mso]><table><![endif]-->x",
				TemplateSections.split(template, NAMES).get("new-account"));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"unpaired BEGIN      | <!--BEGIN new-account-->x",
			"unpaired END        | x<!--END new-account-->",
			"nested section      | <!--BEGIN new-account--><!--BEGIN existing-account-->x<!--END existing-account--><!--END new-account-->",
			"duplicate section   | <!--BEGIN new-account-->x<!--END new-account--><!--BEGIN new-account-->y<!--END new-account-->",
			"unknown name        | <!--BEGIN existing-acount-->x<!--END existing-acount-->",
			"misspelled marker   | <!-- begin new-account -->x<!-- end new-account -->",
			"mismatched END      | <!--BEGIN new-account-->x<!--END existing-account-->"
	})
	void split_malformedMarkers_areRejected(String description, String template) {
		assertThrows(IllegalArgumentException.class, () -> TemplateSections.split(template, NAMES));
	}
}
