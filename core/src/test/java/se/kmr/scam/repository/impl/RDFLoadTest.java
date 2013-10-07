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

package se.kmr.scam.repository.impl; 

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.ContextImpl;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.repository.test.TestSuite;
import org.junit.Before;
import org.junit.Test;

/**
 */
public class RDFLoadTest {
	private RepositoryManagerImpl rm;
	private ContextManager cm;
	private PrincipalManager pm;

	@Before
	public void setup() {
		ConfigurationManager confMan = null;
		try {
			confMan = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
		} catch (IOException e) {
			e.printStackTrace();
		}
		Config config = confMan.getConfiguration(); 
		config.setProperty(Settings.STORE_TYPE, "memory");
		rm = new RepositoryManagerImpl("http://my.confolio.org/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
		TestSuite.initDisneySuite(rm);
		TestSuite.addEntriesInDisneySuite(rm);
		((ContextImpl) cm).getCache().clear();
	}

	@Test
	public void typeCheck() {
		//Donald, check owner of duck and editing rights in mouse.
		pm.setAuthenticatedUserURI(pm.getPrincipalEntry("Donald").getResourceURI());
		Context duck = cm.getContext("duck");

		//ContextManager correct types.
		assertTrue(cm.getEntry().getLocationType() == LocationType.Local);
		assertTrue(cm.getEntry().getBuiltinType() == BuiltinType.SystemContext);
		assertTrue(cm.getEntry().getRepresentationType() == RepresentationType.InformationResource);

		//Duck context
		assertTrue(duck.getEntry().getLocationType() == LocationType.Local);
		assertTrue(duck.getEntry().getBuiltinType() == BuiltinType.Context);
		assertTrue(duck.getEntry().getRepresentationType() == RepresentationType.InformationResource);

		//Top list
		Entry entry = duck.get("_top");
		assertTrue(entry.getLocationType() == LocationType.Local);
		assertTrue(entry.getBuiltinType() == BuiltinType.List);
		assertTrue(entry.getRepresentationType() == RepresentationType.InformationResource);
		
		//LinkReference to Mickeys top list.
		entry = duck.get("3");
		assertTrue("Locationtype should be LinkReference, it is now: "+entry.getLocationType(), 
				entry.getLocationType() == LocationType.LinkReference);
		assertTrue(entry.getBuiltinType() == BuiltinType.List);
		assertTrue(entry.getRepresentationType() == RepresentationType.InformationResource);

		//Reference to the principal Mickey
		entry = duck.get("4");
		assertTrue(entry.getLocationType() == LocationType.Reference);
		assertTrue(entry.getBuiltinType() == BuiltinType.User);
		assertTrue(entry.getRepresentationType() == RepresentationType.InformationResource);

		//Link to wikipedia
		entry = duck.get("6");
		assertTrue(entry.getLocationType() == LocationType.Link);
		assertTrue(entry.getBuiltinType() == BuiltinType.None);
		assertTrue(entry.getRepresentationType() == RepresentationType.InformationResource);

		//Phooey, a abstract resource
		entry = duck.get("8");
		assertTrue(entry.getLocationType() == LocationType.Local);
		assertTrue(entry.getBuiltinType() == BuiltinType.None);
		assertTrue(entry.getRepresentationType() == RepresentationType.NamedResource);
	}
	
	@Test
	public void userChecks() {
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		assertTrue(pm.getGroupUris().size() == 3);
	}
}
