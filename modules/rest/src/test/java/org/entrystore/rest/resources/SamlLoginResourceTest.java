package org.entrystore.rest.resources;

import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Reference;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SamlLoginResourceTest {

	@Mock
	RepositoryManagerImpl rm;

	@Mock
	SamlLoginResource samlLoginResource;

	Config config;

	Request request = new Request(Method.GET, new Reference(""));

	Response response = new Response(request);

	Context context = new Context();

	@BeforeEach
	void beforeEach() {
		config = buildConfig();

		lenient().when(samlLoginResource.getRM()).thenReturn(rm);
		lenient().when(rm.getConfiguration()).thenReturn(config);
		doCallRealMethod().when(samlLoginResource).init(any(Context.class), any(Request.class), any(Response.class));
		doCallRealMethod().when(samlLoginResource).getSamlIDPs();
		lenient().doCallRealMethod().when(samlLoginResource).findIdpForDomain(anyString());
		lenient().doCallRealMethod().when(samlLoginResource).findIdpForRequest(any(Request.class));
	}

	@AfterEach
	void afterEach() throws NoSuchFieldException, IllegalAccessException {
		Field configField = samlLoginResource.getClass().getDeclaredField("config");
		configField.setAccessible(true);
		configField.set(samlLoginResource, null);

		Field samlIdpField = samlLoginResource.getClass().getDeclaredField("samlIDPs");
		samlIdpField.setAccessible(true);
		samlIdpField.set(samlLoginResource, null);

		Field defaultIdp = samlLoginResource.getClass().getDeclaredField("defaultIdp");
		defaultIdp.setAccessible(true);
		defaultIdp.set(samlLoginResource, null);
	}

	@Test
	void initTest() {
		samlLoginResource.init(context, request, response);
		assertThat(samlLoginResource.getSamlIDPs()).hasSize(2);
	}

	@Test
	void findIdpForDomainTest() {
		samlLoginResource.init(context, request, response);
		assertThat(samlLoginResource.findIdpForDomain("entryscape.org")).isEqualTo("entryscape");
		assertThat(samlLoginResource.findIdpForDomain("sub1.customer.se")).isEqualTo("customer");
		assertThat(samlLoginResource.findIdpForDomain("sub2.customer.se")).isEqualTo("customer");
		assertThat(samlLoginResource.findIdpForDomain("fallbacktest.random.tld")).isEqualTo("entryscape");
	}

	@Test
	void findIdpForRequestTest() {
		samlLoginResource.init(context, request, response);

		Request requestIdpEntryscape = new Request(Method.GET, new Reference("https://entryscape.dev/store/auth/saml?idp=entryscape"));
		assertThat(samlLoginResource.findIdpForRequest(requestIdpEntryscape).getId()).isEqualTo("entryscape");

		Request requestIdpCustomer = new Request(Method.GET, new Reference("https://entryscape.dev/store/auth/saml?idp=customer"));
		assertThat(samlLoginResource.findIdpForRequest(requestIdpCustomer).getId()).isEqualTo("customer");

		Request requestIdpUsernameEntryscape = new Request(Method.GET, new Reference("https://entryscape.dev/store/auth/saml?username=abc@entryscape.org"));
		assertThat(samlLoginResource.findIdpForRequest(requestIdpUsernameEntryscape).getId()).isEqualTo("entryscape");

		Request requestIdpUsernameCustomer = new Request(Method.GET, new Reference("https://entryscape.dev/store/auth/saml?username=abc@sub1.customer.se"));
		assertThat(samlLoginResource.findIdpForRequest(requestIdpUsernameCustomer).getId()).isEqualTo("customer");

		Request requestIdpUsernameWildcard = new Request(Method.GET, new Reference("https://entryscape.dev/store/auth/saml?username=abc@random.tld"));
		assertThat(samlLoginResource.findIdpForRequest(requestIdpUsernameWildcard).getId()).isEqualTo("entryscape");

		Request requestIdpNoParamDefaultIdP = buildRequest("https://entryscape.dev/store/auth/saml");
		assertThat(samlLoginResource.findIdpForRequest(requestIdpNoParamDefaultIdP).getId()).isEqualTo("entryscape");
	}

	@Test
	void findIdpForRequestTestNoWildcardDomain() {
		config.getProperties().remove("entrystore.auth.saml.idp.entryscape.domains.2");
		samlLoginResource.init(context, request, response);
		Request requestIdpUsername = buildRequest("https://entryscape.dev/store/auth/saml?username=abc@random.tld");
		assertThat(samlLoginResource.findIdpForRequest(requestIdpUsername)).isNull();
	}

	@Test
	void findIdpForRequestTestNoDefaultIdp() {
		config.getProperties().remove("entrystore.auth.saml.default-idp");
		samlLoginResource.init(context, request, response);
		Request requestIdpNoParam = buildRequest("https://entryscape.dev/store/auth/saml");
		assertThat(samlLoginResource.findIdpForRequest(requestIdpNoParam)).isNull();
	}

	Config buildConfig() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty("entrystore.auth.saml", "new");
		config.setProperty("entrystore.auth.saml.assertion-consumer-service.url", "https://entryscape.dev/store/auth/saml");
		config.setProperty("entrystore.auth.saml.redirect-success.url", "https://entryscape.dev/start");
		config.setProperty("entrystore.auth.saml.redirect-failure.url", "https://entryscape.dev/signin");
		config.setProperty("entrystore.auth.saml.default-idp", "entryscape");
		config.setProperty("entrystore.auth.saml.idps.1", "entryscape");
		config.setProperty("entrystore.auth.saml.idps.2", "customer");
		config.setProperty("entrystore.auth.saml.idp.entryscape.relying-party-id", "EntryScapeDev1");
		config.setProperty("entrystore.auth.saml.idp.entryscape.metadata.url", "https://entryscape.dev/saml.xml");
		config.setProperty("entrystore.auth.saml.idp.entryscape.metadata.max-age", "600");
		config.setProperty("entrystore.auth.saml.idp.entryscape.user-auto-provisioning", "on");
		config.setProperty("entrystore.auth.saml.idp.entryscape.redirect-method", "get");
		config.setProperty("entrystore.auth.saml.idp.entryscape.domains.1", "entryscape.org");
		config.setProperty("entrystore.auth.saml.idp.entryscape.domains.2", "*");
		config.setProperty("entrystore.auth.saml.idp.customer.relying-party-id", "EntryScapeDev2");
		config.setProperty("entrystore.auth.saml.idp.customer.metadata.url", "https://customer.tld/saml2/metadata");
		config.setProperty("entrystore.auth.saml.idp.customer.metadata.max-age", "604800");
		config.setProperty("entrystore.auth.saml.idp.customer.user-auto-provisioning", "off");
		config.setProperty("entrystore.auth.saml.idp.customer.redirect-method", "post");
		config.setProperty("entrystore.auth.saml.idp.customer.domains.1", "sub1.customer.se");
		config.setProperty("entrystore.auth.saml.idp.customer.domains.2", "sub2.customer.se");
		return config;
	}

	Request buildRequest(String uriReference) {
		return new Request(Method.GET, new Reference(uriReference));
	}

}
