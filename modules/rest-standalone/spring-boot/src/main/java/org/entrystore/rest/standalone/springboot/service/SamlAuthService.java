package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.model.auth.SamlIdpInfo;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SamlAuthService {

	private static final Map<String, SamlIdpInfo> samlIDPsConfig = new HashMap<>();

	private static String defaultIdp;
	private static List<String> redirectDomainWhitelist;

	private final Config config;

	@PostConstruct
	public void init() {
		List<String> idps = config.getStringList(Settings.AUTH_SAML_IDPS);
		if (idps == null) {
			return;
		}
		for (String idp : idps) {
			SamlIdpInfo idpInfo = SamlIdpInfo.builder()
					.id(idp)
					.domains(config.getStringList(idpSetting(Settings.AUTH_SAML_IDP_DOMAINS, idp), List.of("*")))
					.autoProvisioning(config.getBoolean(idpSetting(Settings.AUTH_SAML_IDP_USER_AUTO_PROVISIONING, idp), false))
					.build();

			samlIDPsConfig.put(idp, idpInfo);
			logIdpInfo(idpInfo);
		}

		defaultIdp = config.getString(Settings.AUTH_SAML_DEFAULT_IDP);
		log.info("SAML Default IdP: {}", defaultIdp);

		redirectDomainWhitelist = config.getStringList(Settings.AUTH_SAML_REDIRECT_DOMAIN_WHITELIST, new ArrayList<>());
		for (String domain : redirectDomainWhitelist) {
			log.info("Allowed domain for redirects: {}", domain);
		}
	}

	public boolean isValidRedirectUrl(String url) {
		if (StringUtils.isEmpty(url)) {
			return false;
		}
		return redirectDomainWhitelist.contains(URI.create(url).getHost());
	}

	private String idpSetting(String configKey, String idp) {
		return String.format(configKey, idp);
	}

	private void logIdpInfo(SamlIdpInfo info) {
		String prefix = "SAML IdP \"" + info.id() + "\" -";
		log.info("{} Domains: {}", prefix, info.domains());
		log.info("{} Auto Provisioning: {}", prefix, info.autoProvisioning());
	}

	public String findIdpIdForRequest(String username, String idp) {

		if (StringUtils.isNotBlank(username)) {
			String domain = StringUtils.substringAfter(username, "@");
			if (!domain.isEmpty()) {
				return findIdpIdForDomain(domain);
			}
		}
		if (StringUtils.isNotBlank(idp)) {
			return idp;
		}

		if (defaultIdp == null || defaultIdp.isEmpty()) {
			log.warn("IdP parameter missing and no default IdP configured, unable to properly initialize IDP configuration.");
			throw new BadRequestException("Unable to initialize IDP configuration. IdP parameter missing and no default IdP configured.");
		}
		return defaultIdp;
	}

	private String findIdpIdForDomain(String domain) {
		String wildcardIdp = null;
		for (SamlIdpInfo idpInfo : samlIDPsConfig.values()) {
			if (idpInfo.domains().contains("*")) {
				wildcardIdp = idpInfo.id();
			}
			if (idpInfo.domains().contains(domain.toLowerCase())) {
				return idpInfo.id();
			}
		}
		// we return the IDP matching the wildcard only if we cannot find anything more
		// specific for that particular domain, this way we treat wildcards as fallback
		return wildcardIdp;
	}

	public SamlIdpInfo findIdpForSamlResponse(String idpName) {

		if (StringUtils.isNotBlank(idpName)) {
			return samlIDPsConfig.get(idpName);
		}

		if (defaultIdp == null || defaultIdp.isEmpty()) {
			log.warn("IdP parameter missing and no default IdP configured, unable to properly initialize IDP configuration. " +
					"IDP from SAML response: {}", idpName);
			return null;
		}
		return samlIDPsConfig.get(defaultIdp);
	}

}
