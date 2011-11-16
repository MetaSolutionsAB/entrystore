/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.harvesting.fao;

import java.io.InputStream;

import static org.junit.Assert.assertNotNull;
import org.junit.Test; 


/**
 * A class to test that JUnit 4 works with build environment
 *
 * Todo: remove this class before first release
 *
 * @author mlkn
 * @version $Id$
 */
public class SimpleTest {
	@Test
	public void testFindProperty() {
		  InputStream is = getClass().getResourceAsStream( "/log4j.properties" );
		  assertNotNull(is); 
	}
}