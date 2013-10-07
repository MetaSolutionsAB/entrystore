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


package org.entrystore.repository.impl;

import java.io.IOException;

import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.junit.Before;
import org.junit.Test;

public class SCAM2ImportTest {
	private RepositoryManagerImpl rm;
	private ContextManager cm;
	private Context context;
	
  @Before
  public void setup() {
	  ConfigurationManager confMan = null;
	  try {
		  confMan = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
	  } catch (IOException e) {
		  e.printStackTrace();
	  }
	  confMan.getConfiguration().setProperty(Settings.STORE_TYPE, "memory");
	  confMan.getConfiguration().setProperty(Settings.DATA_FOLDER, "/tmp/scam/");
	  rm = new RepositoryManagerImpl("http://my.confolio.org/", confMan.getConfiguration());
	  rm.setCheckForAuthorization(false);
	  cm = rm.getContextManager();
	  //A new Context
	  Entry entry = cm.createResource(null, BuiltinType.Context, null, null);
	  context = (Context) entry.getResource();
  }  
  

  @Test
  public void importPortfolio() {
//	  int entryCount = context.getResources().size();
//	  SCAM2Import test = new SCAM2Import(context, "/home/matthias/tmp/vhu_2008-11-26T22-46_backup/");
//	  test.doImport();
//	  ZipExport ze = new ZipExport(context, "/home/matthias/tmp/vhu2/");
//	  ze.export();
//	  int entryCount2 = context.getResources().size();
//	  assertTrue(entryCount < entryCount2);
  }
}