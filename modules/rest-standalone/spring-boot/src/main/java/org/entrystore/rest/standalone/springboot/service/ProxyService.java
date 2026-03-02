package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;

	private Set<String> whitelistAnon;
	private Set<String> whitelistLocal;

	private static final int MAX_REDIRECTS = 15;
	private static final int CONNECT_TIMEOUT_MS = 30_000;
	private static final int READ_TIMEOUT_MS = 60_000;
	private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

	private static final List<Pattern> BLACKLIST_REGEX = Arrays.asList(
			Pattern.compile("^localhost$"),
			Pattern.compile("(.+)\\.local"),
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
			Pattern.compile("^\\d$"),
			Pattern.compile(":")
	);

	@PostConstruct
	void init() {
		whitelistAnon = loadWhitelist(Settings.PROXY_WHITELIST_ANONYMOUS);
		if (!whitelistAnon.isEmpty()) {
			log.info("Proxy whitelist for guest users initialized with following domains: {}; Requests to other domains require authentication",
					String.join(", ", whitelistAnon));
		} else {
			log.info("No domains provided for proxy whitelist; only authenticated users are allowed to perform proxy requests");
		}

		whitelistLocal = loadWhitelist(Settings.PROXY_WHITELIST_LOCAL);
		if (!whitelistLocal.isEmpty()) {
			log.info("Proxy local whitelist initialized with following domains: {}", String.join(", ", whitelistLocal));
		}

		log.info("Proxy blacklist consists of following regular expressions: {}", BLACKLIST_REGEX);
	}

	private Set<String> loadWhitelist(String settingsKey) {
		Set<String> result = new HashSet<>();
		for (String domain : repositoryManager.getConfiguration().getStringList(settingsKey)) {
			if (domain != null) {
				result.add(domain.toLowerCase());
			}
		}
		return result;
	}

	public String extractHost(String url) {
		try {
			String host = new URI(url).getHost();
			if (host == null) {
				throw new BadRequestException("URL has no host: " + url);
			}
			return host.toLowerCase();
		} catch (URISyntaxException e) {
			throw new BadRequestException("Malformed URL: " + url);
		}
	}

	public void validateGlobalAccess(String host) {
		if (principalManager.getGuestUser().getURI().equals(principalManager.getAuthenticatedUserURI())) {
			if (!whitelistAnon.contains(host)) {
				throw new ForbiddenException("Guest user is not allowed to proxy requests to host: " + host);
			}
		}
	}

	public void validateContextAccess(String contextId) {
		Context context = contextService.getContextOrThrow(contextId);
		principalManager.checkAuthenticatedUserAuthorized(context.getEntry(),
				PrincipalManager.AccessProperty.ReadResource);
	}

	boolean isBlacklisted(String host) {
		host = host.toLowerCase();

		if (whitelistLocal.contains(host)) {
			return false;
		}

		for (Pattern p : BLACKLIST_REGEX) {
			if (p.matcher(host).find()) {
				return true;
			}
		}

		try {
			InetAddress ia = InetAddress.getByName(host);
			if (ia.isAnyLocalAddress() ||
					ia.isSiteLocalAddress() ||
					ia.isLoopbackAddress() ||
					ia.isLinkLocalAddress() ||
					ia.isMulticastAddress()) {
				return true;
			}
		} catch (UnknownHostException e) {
			log.warn(e.getMessage());
			return true;
		}

		return false;
	}

	public ProxyResponse fetchUrl(String url, String acceptHeader) {
		return fetchUrl(url, acceptHeader, 0);
	}

	private ProxyResponse fetchUrl(String url, String acceptHeader, int redirectCount) {
		String host = extractHost(url);

		if (isBlacklisted(host)) {
			throw new ForbiddenException("Access denied: host is blacklisted");
		}

		if (redirectCount > MAX_REDIRECTS) {
			log.warn("More than {} redirect loops detected, aborting", MAX_REDIRECTS);
			throw new CustomResponseException("Too many redirects", HttpStatus.BAD_GATEWAY);
		}

		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URI(url).toURL().openConnection();
			conn.setRequestMethod("GET");
			conn.setInstanceFollowRedirects(false);
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			if (acceptHeader != null) {
				conn.setRequestProperty("Accept", acceptHeader);
			}

			int status = conn.getResponseCode();

			if (status >= 300 && status < 400) {
				String location = conn.getHeaderField("Location");
				if (location != null) {
					log.debug("Request redirected to {}", location);
					return fetchUrl(location, acceptHeader, redirectCount + 1);
				}
				log.warn("Upstream returned {} redirect without Location header for URL: {}", status, url);
				throw new CustomResponseException("Upstream returned redirect without Location header", HttpStatus.BAD_GATEWAY);
			}

			String contentType = conn.getContentType();
			byte[] body;
			try (InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
				body = (is != null) ? readWithLimit(is) : new byte[0];
			}

			return new ProxyResponse(status, contentType, body);

		} catch (SocketTimeoutException | ConnectException e) {
			log.debug("Proxy request to {} timed out", url);
			throw new CustomResponseException("Gateway timeout", HttpStatus.GATEWAY_TIMEOUT);
		} catch (IOException | URISyntaxException e) {
			log.debug("Proxy request to {} failed: {}", url, e.getMessage());
			throw new CustomResponseException("Proxy request failed", HttpStatus.BAD_GATEWAY);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private byte[] readWithLimit(InputStream is) throws IOException {
		byte[] buf = new byte[8192];
		int totalRead = 0;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int bytesRead;
		while ((bytesRead = is.read(buf)) != -1) {
			totalRead += bytesRead;
			if (totalRead > MAX_RESPONSE_BYTES) {
				throw new CustomResponseException("Upstream response exceeds maximum allowed size of " + MAX_RESPONSE_BYTES + " bytes",
						HttpStatus.BAD_GATEWAY);
			}
			out.write(buf, 0, bytesRead);
		}
		return out.toByteArray();
	}
}
