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

package se.kmr.scam.harvesting.oaipmh.integrationtest;

import java.net.URL;

import org.codehaus.cargo.container.ContainerType;
import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.configuration.ConfigurationType;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.deployable.Deployable;
import org.codehaus.cargo.container.deployable.DeployableType;
import org.codehaus.cargo.container.installer.ZipURLInstaller;
import org.codehaus.cargo.container.property.ServletPropertySet;
import org.codehaus.cargo.generic.DefaultContainerFactory;
import org.codehaus.cargo.generic.configuration.ConfigurationFactory;
import org.codehaus.cargo.generic.configuration.DefaultConfigurationFactory;
import org.codehaus.cargo.generic.deployable.DefaultDeployableFactory;
import org.codehaus.cargo.util.log.FileLogger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;


/**
 * <p>TestSuite class which uses Cargo to start
 * and stop a Servlet container.</p>
 *  
 *  View Cargo Core API <a href="http:http://cargo.codehaus.org/maven-site/cargo-core/cargo-core-api/cargo-core-api-container/apidocs/overview-summary.html">here</a>
 *
 * @author micke
 *
 */
@RunWith(Suite.class)
@SuiteClasses( { SimpleIntegrationTest.class })
public class TestSuite {

	/**
     * <p>Local container for this test setup.</p>
     */
    private static InstalledLocalContainer container;

    
    /**
     * <p>Start container prior to running integrationtests.</p>
     *
     * @throws Exception if an error occurs.
     */
    @BeforeClass
    public static void setUp() throws Exception {
    	String containerId = System.getProperty("cargo.container"); // container id, example: Tomcat5x 
    	String deployablePath = System.getProperty("cargo.deployable"); // path to war file
    	String port = System.getProperty("cargo.servlet.port"); 
    	String containerHome = System.getProperty("cargo.container.home"); 
    	String containerLog = System.getProperty("cargo.container.log");
    	String containerOutput = System.getProperty("cargo.container.output");
    	String installDir = System.getProperty("cargo.installDir");
    	String containerDownloadUrl = System.getProperty("cargo.container.download.url");
    		
    	// Optional step to install the container from a URL pointing to its distribution
    	if (containerDownloadUrl != null && !containerDownloadUrl.equals("")) {
    		ZipURLInstaller installer = new ZipURLInstaller(new URL(containerDownloadUrl));
    		if (installDir != null) {
    			installer.setInstallDir(installDir);
    		}
    		if (installer.isAlreadyInstalled() ) {
    			System.out.println("Found container!");
    		} else  {
    			System.out.println("Download and install Container...");
    			installer.install();
    			containerHome = installer.getHome();
    			System.out.println("Container installed!");
    		}
    	}
        
    	System.out.println("[CARGO DEPLOY INFO]");
    	System.out.println("container id: " + containerId);
    	System.out.println("deploy: " + deployablePath);
    	System.out.println("servlet port: " + port);
        System.out.println("container home: " + containerHome);
        System.out.println("container log: " + containerLog);
        System.out.println("container output: " + containerOutput);
    	
        // Construct the war
        Deployable war = new DefaultDeployableFactory().createDeployable(
                containerId,
                deployablePath,
                DeployableType.WAR);

        //LocalConfiguration  configuration = new AbstractStandaloneLocalConfiguration
        
        // Create the Cargo Container instance wrapping the physical container
        ConfigurationFactory configurationFactory = new DefaultConfigurationFactory();
     
        LocalConfiguration configuration =
                (LocalConfiguration) configurationFactory.createConfiguration(
                        containerId, 
                        ContainerType.INSTALLED,
                        ConfigurationType.STANDALONE);
  
        configuration.setProperty(ServletPropertySet.PORT, port); 	 
        
        configuration.addDeployable(war);

        container = (InstalledLocalContainer)
                new DefaultContainerFactory().createContainer(
                        containerId,
                        ContainerType.INSTALLED, configuration);

        container.setHome(containerHome);

        // Logging
        container.setLogger(new FileLogger(containerLog,false));
        container.setOutput(containerOutput);

        // Start the container
        container.start();
    }
    
    /**
    * Stop the container after running the tests.
    *
    * @throws Exception if an error occurs.
    */
    @AfterClass
    public static void after() throws Exception {
    	 container.stop();
    }
}
